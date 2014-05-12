(ns flense.ui
  (:refer-clojure :exclude [chars rem])
  (:require [cljs.core.async :as async]
            [clojure.string :as string]
            [flense.edit :as e]
            [flense.keyboard :refer [key-data]]
            [flense.parse :as p]
            [flense.zip :as z]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defn- class-list [{:keys [selected? type] :as data}]
  (string/join " "
   [(name type)
    (if (p/coll-node? data) "coll" "token")
    (when selected? "selected")]))

(defn- move-caret-to-end [input]
  (let [idx (count (.-value input))]
    (set! (.-selectionStart input) idx)
    (set! (.-selectionEnd input) idx)))

(defn- fully-selected? [input]
  (and (= (.-selectionStart input) 0)
       (= (.-selectionEnd input) (count (.-value input)))))

(def ^:private MAX_CHARS 72)

(defn- line-count [text]
  (inc (int (/ (count text) (- MAX_CHARS 2)))))

(defn- px [n]
  (str n "px"))

(defn- rem [n]
  (str n "rem"))

(defn- render-width [content]
  (let [tester (.getElementById js/document "width-tester")]
    (set! (.-textContent tester) content)
    (inc (.-clientWidth tester))))

(def ^:private MAX_WIDTH
  (render-width (string/join (repeat MAX_CHARS "_"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; token views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handle-key [ev]
  (when (and (= (key-data ev) #{:BACKSPACE})
             (not (fully-selected? (.-target ev))))
    (.stopPropagation ev)))

(defn- token-view [data owner]
  (reify
    om/IRender
    (render [_]
      (dom/input
        #js {:className (class-list data)
             :onChange #(om/update! data (p/parse-token (.. % -target -value)))
             :onKeyDown handle-key
             :style #js {:width (px (render-width (p/tree->str data)))}
             :value (:text data)}))
    om/IDidMount
    (did-mount [_]
      (when (:selected? data) (doto (om/get-node owner) .focus .select)))
    om/IDidUpdate
    (did-update [_ prev _]
      (let [input (om/get-node owner)]
        (if (:selected? data)
            (when (or (not (:selected? prev)) (p/placeholder-node? data))
              (doto input .focus .select))
            (when (:selected? prev) (.blur input)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; string content views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handle-string-key [ev]
  (when (#{#{:BACKSPACE}
           #{:LEFT}
           #{:OPEN_BRACKET}
           #{:RIGHT}
           #{:SPACE}
           #{:SHIFT :NINE}
           #{:SHIFT :OPEN_BRACKET}
           #{:SHIFT :QUOTE}
           #{:SHIFT :THREE}} (key-data ev))
    (.stopPropagation ev)))

(defn- string-content-view [data owner]
  (reify
    om/IRender
    (render [_]
      (let [text (string/replace (:text data) #"\s+" " ")]
        (dom/textarea
          #js {:className (class-list data)
               :onChange #(om/update! data :text (.. % -target -value))
               :onKeyDown handle-string-key
               :style #js {:height (str (* 1.2 (line-count text)) "rem")
                           :width  (px (min (render-width text) MAX_WIDTH))}
               :value text})))
    om/IDidMount
    (did-mount [_]
      (when (:selected? data)
        (let [input (om/get-node owner)]
          (if (p/placeholder-node? data)
              (doto input .focus .select)
              (move-caret-to-end input)))))
    om/IDidUpdate
    (did-update [_ prev _]
      (let [input (om/get-node owner)]
        (if (:selected? data)
            (when-not (:selected? prev) (move-caret-to-end input))
            (when (:selected? prev) (.blur input)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; seq views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare node-view)

(defn- chars [tree]
  (count (p/tree->str tree)))

(defn- head-count [items]
  (let [itemc (count items)]
    (loop [offset 1 idxs (range itemc)]
      (if-let [idx (first idxs)]
        (let [item-width (chars (nth items idx))
              offset' (+ offset 1 item-width)]
          (if (> offset' MAX_CHARS) idx (recur offset' (rest idxs))))
        itemc))))

(defn- seq-view*
  "Constructs and returns a seq view whose contents are formatted according to
   the format specification passed as `opts`."
  [data owner opts]
  (reify om/IRender
    (render [_]
      (let [{:keys [always-multiline? fixed-head-count indent]} opts
            merge-props (when-not always-multiline? {:enclosing owner})
            multiline?  (or always-multiline? (> (chars data) MAX_CHARS))
            indent   (if (instance? js/Function indent) (indent data) indent)
            children (:children data)
            headc (or (when multiline? fixed-head-count) (head-count children))
            heads (map #(merge % merge-props) (take headc children))
            tails (map #(merge % merge-props) (drop headc children))]
        (apply dom/div #js {:className (class-list data)}
         (concat (om/build-all node-view heads)
                 [(apply dom/div
                   #js {:className "runoff-children"
                        :style #js {:margin-left indent}}
                   (om/build-all node-view tails))]))))))

(def ^:private special-formats
  "Formatting options for clojure.core macros that are commonly indented in a
   manner not consistent with the standard function call indentation style."
  {"defmacro"  {:fixed-head-count 2 :indent "1rem" :always-multiline? true}
   "defn"      {:fixed-head-count 2 :indent "1rem" :always-multiline? true}
   "defn-"     {:fixed-head-count 2 :indent "1rem" :always-multiline? true}
   "do"        {:fixed-head-count 2 :indent "2rem"}
   "if"        {:fixed-head-count 2 :indent "2rem"}
   "if-let"    {:fixed-head-count 2 :indent "1rem" :always-multiline? true}
   "let"       {:fixed-head-count 2 :indent "1rem" :always-multiline? true}
   "loop"      {:fixed-head-count 2 :indent "1rem" :always-multiline? true}
   "ns"        {:fixed-head-count 2 :indent "1rem" :always-multiline? true}
   "when"      {:fixed-head-count 2 :indent "1rem"}
   "when-let"  {:fixed-head-count 2 :indent "1rem" :always-multiline? true}})

(defn- indent-size [tree]
  (let [head (first (:children tree))]
    (rem (if (#{:keyword :symbol} (:type head))
             (* .5 (+ 2 (chars head)))
             1))))

(defn- seq-view [data owner]
  (reify om/IRender
    (render [_]
      (let [head (first (:children data))]
        (om/build seq-view* data
         {:opts (get special-formats (:text head) {:indent indent-size})})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; collection, generic, root views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- coll-view [data owner]
  (reify om/IRender
    (render [_]
      (apply dom/div #js {:className (class-list data)}
        (om/build-all node-view (:children data))))))

(defn- node-view [data owner]
  (reify
    om/IRender
    (render [_]
      (om/build
       (cond (= (:type data) :seq) seq-view
             (p/coll-node? data) coll-view
             (= (:type data) :string-content) string-content-view
             :else token-view)
       data))
    om/IDidUpdate
    (did-update [_ _ _]
      (when-let [enclosing-view (:enclosing data)]
        (om/refresh! enclosing-view)))))

(defn root-view [app-state owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [tx-chan (om/get-shared owner :tx-chan)]
        (go-loop []
          (let [tx (<! tx-chan)]
            (om/transact! app-state (or (:path tx) []) (:fn tx) (:tag tx)))
          (recur))))
    om/IRender
    (render [_]
      (let [{:keys [tree]} (z/edit app-state assoc :selected? true)]
        (apply dom/div #js {:className "flense"}
          (om/build-all node-view (:children tree)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; command bar view
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handle-command-bar-key [command-chan ev]
  (condp = (key-data ev)
    #{:ENTER} (let [input (.-target ev)]
                (async/put! command-chan (string/split (.-value input) #"\s+"))
                (set! (.-value input) "")
                (.blur input))
    #{:ESC} (.. ev -target blur) nil)
  (.stopPropagation ev)) ; allow default behavior instead of keybound

(defn command-bar-view [_ owner]
  (reify om/IRender
    (render [_]
      (dom/input
        #js {:id "command-bar"
             :onKeyDown (partial handle-command-bar-key
                         (om/get-shared owner :command-chan))}))))

(ns flense.ui.editor
  (:refer-clojure :exclude [chars rem])
  (:require [cljs.core.async :as async]
            [clojure.string :as string]
            [flense.keyboard :refer [key-data]]
            [flense.parse :as p]
            [flense.util.dom :as udom :refer [px rem]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [xyzzy.core :as z])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defn- class-list [{:keys [selected? type] :as data}]
  (string/join " " [(name type)
                    (if (p/coll-node? data) "coll" "token")
                    (when selected? "selected")]))

(def ^:private MAX_CHARS 72)

(defn- line-count [text]
  (inc (int (/ (count text) (- MAX_CHARS 2)))))

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
             (not (udom/fully-selected? (.-target ev))))
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
      (when (:selected? data) (udom/focus+select (om/get-node owner))))
    om/IDidUpdate
    (did-update [_ prev _]
      (let [input (om/get-node owner)]
        (if (:selected? data)
            (when (or (not (:selected? prev)) (p/placeholder-node? data))
              (udom/focus+select input))
            (when (:selected? prev) (.blur input)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; string content views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handle-string-key [ev]
  (when-not (#{#{:ENTER} #{:UP}} (key-data ev))
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
               :style #js {:height (rem (* 1.2 (line-count text)))
                           :width  (px (min (render-width text) MAX_WIDTH))}
               :value text})))
    om/IDidMount
    (did-mount [_]
      (when (:selected? data)
        (let [input (om/get-node owner)]
          (if (p/placeholder-node? data)
              (udom/focus+select input)
              (udom/move-caret-to-end input)))))
    om/IDidUpdate
    (did-update [_ prev _]
      (let [input (om/get-node owner)]
        (if (:selected? data)
            (when-not (:selected? prev) (udom/move-caret-to-end input))
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

(defn- indent-size [tree]
  (let [head (first (:children tree))]
    (if (#{:keyword :symbol} (:type head))
        (+ 2 (chars head))
        2)))

(defn- seq-view*
  "Constructs and returns a seq view whose contents are formatted according to
   the format specification passed as `opts`."
  [data owner opts]
  (reify om/IRender
    (render [_]
      (let [{:keys [always-multiline? fixed-head-count indent]
             :or   {indent (indent-size data)}} opts
            merge-props (when-not always-multiline? {:enclosing owner})
            multiline?  (or always-multiline? (> (chars data) MAX_CHARS))  
            children    (:children data)
            headc (or (when multiline? fixed-head-count) (head-count children))
            heads (map #(merge % merge-props) (take headc children))
            tails (map #(merge % merge-props) (drop headc children))]
        (apply dom/div #js {:className (class-list data)}
         (concat (om/build-all node-view heads)
                 [(apply dom/div
                   #js {:className "runoff-children"
                        :style #js {:margin-left (rem (/ indent 2))}}
                   (om/build-all node-view tails))]))))))

(def ^:private special-formats
  "Formatting options for clojure.core macros that are commonly indented in a
   manner not consistent with the standard function call indentation style."
  {"defmacro"  {:fixed-head-count 2 :indent 2 :always-multiline? true}
   "defn"      {:fixed-head-count 2 :indent 2 :always-multiline? true}
   "defn-"     {:fixed-head-count 2 :indent 2 :always-multiline? true}
   "do"        {:fixed-head-count 2 :indent 4}
   "if"        {:fixed-head-count 2 :indent 4}
   "if-let"    {:fixed-head-count 2 :indent 2 :always-multiline? true}
   "let"       {:fixed-head-count 2 :indent 2 :always-multiline? true}
   "loop"      {:fixed-head-count 2 :indent 2 :always-multiline? true}
   "ns"        {:fixed-head-count 2 :indent 2 :always-multiline? true}
   "when"      {:fixed-head-count 2 :indent 2}
   "when-let"  {:fixed-head-count 2 :indent 2 :always-multiline? true}})

(defn- seq-view [data owner]
  (reify om/IRender
    (render [_]
      (om/build seq-view* data
       {:opts (get special-formats (-> data :children first :text))}))))

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
        (om/refresh! enclosing-view))
      (when (:selected? data)
        (let [el (om/get-node owner)]
          (when-not (udom/in-view? el) (udom/scroll-into-view el)))))))

(defn editor-view [app-state owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [edit-chan (om/get-shared owner :edit-chan)]
        (go-loop []
          (let [{pred :when, :keys [edit tags]} (<! edit-chan)]
            (om/transact! app-state [] #(if (pred %) (edit %) %) tags))
          (recur))))
    om/IRender
    (render [_]
      (let [{:keys [tree]} (z/edit app-state assoc :selected? true)]
        (apply dom/div #js {:className "editor"}
          (om/build-all node-view (:children tree)))))))
(ns flense.ui
  (:require [clojure.string :as string]
            [flense.edit :as e]
            [flense.parse :as p]
            [flense.zip :as z]
            [goog.events.KeyCodes :as key]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(def ^:dynamic *root-cursor*)

(defn- class-list [{:keys [selected? type] :as node}]
  (->> [(name type)
        (if (p/coll-node? node) "coll" "atom")
        (when selected? "selected")]
       (string/join " ")
       string/trimr))

(defn- atom-width [form]
  (let [tester (.getElementById js/document "width-tester")]
    (set! (.-textContent tester) (pr-str form))
    (str (inc (.-clientWidth tester)) "px")))

(defn- handle-key [ev data]
  (condp = (.-keyCode ev)
    key/BACKSPACE
    (let [input (.-target ev)]
      (when (or (not= (.-selectionStart input) 0)
                (not= (.-selectionEnd input) (count (.-value input))))
        (.stopPropagation ev)))

    key/NINE
    (when (.-shiftKey ev)
      (.preventDefault ev)
      (om/transact! data [] (partial e/wrap-sexp :seq) :insert-coll))

    key/OPEN_SQUARE_BRACKET
    (do (.preventDefault ev)
        (om/transact! data []
                      (partial e/wrap-sexp (if (.-shiftKey ev) :map :vec))
                      :insert-coll))
    
    nil)) ; deliberate no-op

(defn- atom-view [node owner]
  (reify
    om/IRender
    (render [_]
      (dom/input
        #js {:className (class-list node)
             :onChange  #(om/update! node (p/parse-atom (.. % -target -value)))
             :onKeyDown #(handle-key % node)
             :style #js {:width (atom-width (:form node))}
             :value (pr-str (:form node))}))

    om/IDidMount
    (did-mount [_]
      (when (:selected? node)
        (doto (om/get-node owner)
          (.focus)
          (.select))))

    om/IDidUpdate
    (did-update [_ prev-props prev-state]
      (if (:selected? node)
          (when (or (not (:selected? prev-props)) (= (:form node) '...))
            (doto (om/get-node owner)
              (.focus)
              (.select)))
          (when (:selected? prev-props)
            (.blur (om/get-node owner)))))))

(declare node-view)

(defn- coll-view [node owner]
  (reify
    om/IRender
    (render [this]
      (apply dom/div #js {:className (class-list node)}
        (om/build-all node-view (:children node))))))

(defn- node-view [node owner]
  (reify
    om/IRender
    (render [this]
      (om/build
        (if (p/coll-node? node) coll-view atom-view)
        node))))

(defn root-view [app-state owner]
  (reify
    om/IRender
    (render [_]
      (set! *root-cursor* app-state)
      (let [{:keys [tree]} (z/edit app-state assoc :selected? true)]
        (apply dom/div #js {:className "flense"}
          (om/build-all node-view (:children tree)))))))
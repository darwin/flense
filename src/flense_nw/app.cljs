(ns flense-nw.app
  (:require [cljs.core.async :as async :refer [<!]]
            [cljs.reader :as rdr]
            [flense.actions :refer [actions defaction]]
            [flense.actions.history :as hist]
            flense.actions.clipboard
            flense.actions.clojure
            flense.actions.movement
            flense.actions.paredit
            [flense.editor :refer [editor-view]]
            flense.editor.layout
            [flense.model :as model]
            [flense-nw.cli :refer [cli-view]]
            [flense-nw.error :refer [error-bar-view]]
            fs
            [om.core :as om]
            [phalanges.core :as phalanges])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(enable-console-print!)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; top-level state setup and management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private app-state
  (atom
   {:path [0]
    :tree {:children
           [(model/form->tree '(fn greet [name] (str "Hello, " name "!")))]}}))

(def ^:private edit-chan (async/chan))
(def ^:private error-chan (async/chan))

(defn raise!
  "Display error message `mparts` to the user in the popover error bar."
  [& mparts]
  (async/put! error-chan (apply str mparts)))

(defn open!
  "Load the source file at `fpath` and open the loaded document, discarding any
   changes made to the previously active document."
  [fpath]
  (reset! app-state
    {:path [0]
     :tree {:children
            (->> (fs/slurp fpath) model/string->forms (mapv model/form->tree))}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; text commands
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti handle-command (fn [command & _] command))

(defmethod handle-command :default [command & _]
  (raise! "Invalid command \"" command \"))

(defmethod handle-command "exec" [_ & args]
  (if-let [name (first args)]
    (if-let [action (-> name rdr/read-string (@actions))]
      (async/put! edit-chan action)
      (raise! "Invalid action \"" name \"))
    (raise! "Must specify an action to execute")))

(defmethod handle-command "open" [_ & args]
  (if-let [fpath (first args)]
    (open! fpath)
    (raise! "Must specify a filepath to open")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; keybinds
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *keymap*)

(defn- bound-action [ev]
  (-> ev phalanges/key-set *keymap* (@actions)))

(defaction :flense/text-command :edit identity) ; dummy action to trap ctrl+x keybind

(defn- handle-key [ev]
  (when-let [action (bound-action ev)]
    (if (= (:name action) :flense/text-command)
      (.. js/document (getElementById "cli") focus)
      (do (.preventDefault ev)
          (async/put! edit-chan action)))))

(defn- fully-selected? [input]
  (and (= (.-selectionStart input) 0)
       (= (.-selectionEnd input) (count (.-value input)))))

(defn- propagate-keypress? [ev form]
  (when-let [action (bound-action ev)]
    (if (model/stringlike? form)
      ;; prevent all keybinds except those that end editing
      (#{:flense/text-command :move/up :paredit/insert-outside} (:name action))
      ;; prevent delete keybind unless text fully selected
      (or (not= (:name action) :flense/remove)
          (fully-selected? (.-target ev))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; application setup and wiring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handle-tx [{:keys [new-state tag] :or {tag #{}}}]
  (when-not (tag :history)
    (hist/push-state! new-state)))

(defn init []
  (set! *keymap* (rdr/read-string (fs/slurp "resources/config/keymap.edn")))
  (let [command-chan (async/chan)]
    (hist/push-state! @app-state)
    (om/root editor-view app-state
             {:target (.getElementById js/document "editor-parent")
              :opts {:edit-chan edit-chan
                     :propagate-keypress? propagate-keypress?}
              :tx-listen handle-tx})
    (om/root cli-view nil
             {:target (.getElementById js/document "cli-parent")
              :shared {:command-chan command-chan}})
    (om/root error-bar-view nil
             {:target (.getElementById js/document "error-bar-parent")
              :shared {:error-chan error-chan}})
    (go-loop []
      (let [[command & args] (<! command-chan)]
        (apply handle-command command args))
      (recur))
    (.addEventListener js/window "keydown" handle-key)))

(init)
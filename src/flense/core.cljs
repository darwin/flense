(ns flense.core
  (:require [flense.edit :as e]
            [flense.render :as render]
            [flense.util :refer [coll-node? form->tree]]
            [flense.zip :as z]
            [goog.events.KeyCodes :as key]
            [om.core :as om]))

(enable-console-print!)

(def app-state
  (atom
   {:path [0]
    :tree {:children
           [(form->tree '(fn greet [name] (str "Hello, " name "!")))]}}))

(def ^:private placeholder
  (form->tree '...))

;; keybinds

(def default-binds
  {key/DOWN   z/down-or-stay
   key/LEFT   z/left-or-wrap
   key/RIGHT  z/right-or-wrap
   key/SPACE  #(e/insert-right % placeholder)
   key/UP     z/up-or-stay})

(defn handle-key [ev]
  (let [keybinds default-binds]
    (when-let [exec-bind (get keybinds (.-keyCode ev))]
      (.preventDefault ev)
      (swap! app-state exec-bind))))

;; application setup and wiring

(defn init []
  (om/root render/root-view app-state
           {:target (.getElementById js/document "flense-parent")
            :tx-listen #(when (= (:tag %) :insert-coll)
                          (swap! app-state z/down))})
  (.addEventListener js/window "keydown" handle-key))

(init)

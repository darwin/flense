(ns flense.core
  (:use [flense.keys :only [ev->key held? trap-modal-keys!]]))

(enable-console-print!)

(def $ js/$)

(defn $empty? [$elem]
  (= (.-length $elem) 0))

;; selection management

(def selected (atom ($ ".selected")))

(defn- select-text! [elem]
  (let [text-range     (.createRange js/document)
        text-selection (.getSelection js/window)]
    (.selectNodeContents text-range elem)
    (.removeAllRanges text-selection)
    (.addRange text-selection text-range)))

(defn enable-editing! [$elem]
  (when (.hasClass $elem "token")
    (.attr $elem "contenteditable" true)
    (let [elem (.get $elem 0)]
      (.focus elem)
      (select-text! elem))))

(defn disable-editing! [$elem]
  (when (.hasClass $elem "token")
    (.removeAttr $elem "contenteditable")
    (.blur (.get $elem 0))))

(defn select! [$elem]
  (let [$prev @selected]
    (disable-editing! $prev)
    (when (and (.hasClass $prev "token") (#{"" "..."} (.text $prev)))
      (.remove $prev))
    (.removeClass $prev "selected"))
  (reset! selected $elem)
  (.addClass $elem "selected")
  (when (.hasClass $elem "token")
    (enable-editing! $elem)))

;; zipper navigation commands

(defn go-down! []
  (let [$selected @selected]
    (when (.hasClass $selected "coll")
      (let [$items (.children $selected)]
        (when-not ($empty? $items)
          (select! (.first $items)))))))

(defn go-up! []
  (let [$selected @selected
        $parent   (.parent $selected)]
    (when-not (.hasClass $parent "flense")
      (select! $parent))))

(defn go-left! []
  (let [$selected @selected
        $prev     (.prev $selected)]
    (if ($empty? $prev)
        (let [$last (-> $selected (.siblings) (.last))]
          (when-not ($empty? $last)
            (select! $last)))
        (select! $prev))))

(defn go-right! []
  (let [$selected @selected
        $next     (.next $selected)]
    (if ($empty? $next)
        (let [$first (-> $selected (.siblings) (.first))]
          (when-not ($empty? $first)
            (select! $first)))
        (select! $next))))

;; structure editing commands

(defn- render-token []
  "<span class=\"token\">...</span>")

(defn- render-coll [type]
  (str "<div class=\"coll " (name type) "\">"
       (render-token)
       "</div>"))

(defn open-coll! [type]
  (let [$selected @selected]
    (.after $selected (render-coll type))
    (go-right!)
    (go-down!)))

(defn break-token! []
  (let [$selected @selected]
    (.after $selected (render-token))
    (go-right!)))

(defn delete-selected! []
  (let [$deleted @selected]
    (if (= (.-length (.siblings $deleted)) 0)
        (go-up!)
        (go-left!))
    (.remove $deleted)))

(defn- selected-range []
  (-> js/window (.getSelection) (.getRangeAt 0)))

(defn- emulate-backspace!
  "Emulates the native backspace text editing command, removing the last
   character of the text in `$elem`."
  [$elem]
  (let [text (.text $elem)]
    (.text $elem (subs text 0 (dec (count text))))
    ;; move cursor to end of elem content
    (let [sel-range
          (doto (selected-range)
            (.selectNodeContents (.get $elem 0))
            (.collapse false))]
      (doto (.getSelection js/window)
        (.removeAllRanges)
        (.addRange sel-range)))))

;; keybinds

(def default-binds
  { ;; structural editing commands
   :DEL
   (fn [ev]
     (let [$selected @selected]
       (if (and (.hasClass $selected "token")
                (not (#{"" "..."} (.text $selected)))
                (empty? (.toString (selected-range))))
           (emulate-backspace! $selected)
           (delete-selected!))))
   :LBRAK           (partial open-coll! :vec)
   :SPACE           break-token!
   #{:SHIFT :NUM_9} (partial open-coll! :seq)
   #{:SHIFT :LBRAK} (partial open-coll! :map)
    ;; simple navigation commands
   :DOWN  go-down!
   :LEFT  go-left!
   :RIGHT go-right!
   :UP    go-up!})

(def modal-keys #{:ALT :CTRL :SHIFT})

(defn handle-key [ev]
  (let [keys (->> modal-keys (filter held?) (reduce conj #{(ev->key ev)}))
        keys (if (= (count keys) 1) (first keys) keys)]
    (when-let [exec-bind! (get default-binds keys)]
      (.preventDefault ev)
      (exec-bind! ev))))

;; application setup and wiring

(defn init []
  (trap-modal-keys! modal-keys)
  (.keydown ($ js/window) handle-key))

(init)

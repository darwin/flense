(ns flense.ui.editor.layout
  (:require [flense.ui.editor :refer [delimiters ->lines ->lines* spacer ->tokens]]))

(defn- update-last [v f & args]
  (conj (pop v) (apply f (peek v) args)))

;; simple "header+body" layout is good enough for most core macros

(defn header+body->lines [form headc]
  (let [[opener closer] (delimiters form)
        [inits rests] (split-at headc (:children form))
        init-line (concat opener (apply concat (interpose (spacer) (map ->tokens inits))))
        rest-lines (map #(concat (spacer 2) %) (mapcat ->lines rests))
        lines `[~init-line ~@rest-lines]]
    (update-last lines concat closer)))

(def header-counts
  '{case 2, cond 1, condp 3, def 2, definline 2, definterface 2, defmacro 2, defmethod 3,
    defmulti 2, defn 2, defn- 2, defonce 2, defprotocol 2, defrecord 3, defstruct 2, deftype 3,
    dotimes 2, if 2, if-let 2, if-not 2, if-some 2, when 2, when-first 2, when-let 2, when-not 2,
    when-some 2})

(doseq [[core-macro headc] header-counts]
  (defmethod ->lines* core-macro [form] (header+body->lines form headc)))

;; letlike core macros need their own layout algorithm

(defn bpair->tokens [[bform bval]]
  (concat (->tokens bform) (spacer) (->tokens bval)))

(defn bvec->lines [form]
  (let [[opener closer] (delimiters form)
        children (:children form)
        pairs (partition 2 children)
        extra (when (odd? (count children)) (last children))]
    (if (seq pairs)
      (let [init-line (concat opener (bpair->tokens (first pairs)))
            rest-lines (mapv #(concat (spacer) (bpair->tokens %)) (rest pairs))
            rest-lines (cond-> rest-lines extra (conj (concat (spacer) (->tokens extra))))
            lines (vec (concat [init-line] rest-lines))]
        (update-last lines concat closer))
      [(concat opener (when extra (->tokens extra)) closer)])))

(defn letlike->lines [form]
  (let [[head bvec & body] (:children form)]
    (if (= (:type bvec) :vec)
      (let [[opener closer] (delimiters form)
            bvec-lines (bvec->lines bvec)
            body-lines (mapv #(concat (spacer 2) %) (mapcat ->lines body))
            body-lines (update-last body-lines concat closer)
            init-line (concat opener (->tokens head) (spacer) (first bvec-lines))
            bvec-indent (spacer (+ (count (:text head)) 2))
            bvec-lines (map #(concat bvec-indent %) (rest bvec-lines))]
        `[~init-line ~@bvec-lines ~@body-lines])
      ((get-method ->lines* :default) form))))

(doseq [letlike '[binding doseq for let loop]]
  (defmethod ->lines* letlike [form] (letlike->lines form)))

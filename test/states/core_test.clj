(ns states.core-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [states.core :refer :all]))

(defspec prop-vector-conj-pop-peek
  (letfn [(commands [{:keys [vec]}]
            (if vec
              (gen/one-of [(gen/tuple (gen/return 'conj)
                                      (gen/return vec)
                                      gen/int)
                           (gen/return ['pop vec])
                           (gen/return ['peek vec])])
              (gen/return '[vector])))
          (precondition [{:keys [elems]} [fn & _]]
            (case fn
              pop (seq elems)
              peek (seq elems)
              true))
          (next-step [state var [fn & args]]
            (case fn
              vector (assoc state :vec var)
              conj (-> state
                       (update-in [:elems] conj (second args))
                       (assoc :vec var))
              pop (-> state
                      (update-in [:elems] rest)
                      (assoc :vec var))
              peek state))
          (postcondition [{:keys [elems] :as s} [fn & args :as cmd] val]
            (case fn
              pop (every? true? (map = val (reverse elems)))
              peek (some #{val} elems)
              true))]
    (run-commands commands precondition next-step postcondition
      {:max-tries 50})))

(defn ^:private java-hash-set []
  (java.util.HashSet.))

(defmacro ^:private def-java-methods [methods]
  (cons 'do
        (for [method methods]
          `(defn ~(symbol (str "java-" method)) [set# elem#]
             (. set# ~method elem#)))))

(def-java-methods [add contains remove])

(defspec prop-java-hash-set
  (letfn [(commands [{:keys [set items]}]
            (if set
              (gen/tuple
                (gen/elements `[java-contains
                                java-add
                                java-remove])
                (gen/return set)
                (gen/one-of [gen/int
                             (gen/elements (cons 0 items))]))
              (gen/return `[java-hash-set])))
          (precondition [& _]
            true)
          (next-step [state var [fn & args]]
            (condp = fn
              `java-hash-set (assoc state :set var :items #{})
              `java-add (update-in state [:items] conj (second args))
              `java-remove (update-in state [:items] disj (second args))
              state))
          (postcondition [{:keys [items] :as state} [fn & args] val]
            (if (= fn `java-contains)
              (= val (contains? items (second args)))
              true))]
    (run-commands commands precondition next-step postcondition)))

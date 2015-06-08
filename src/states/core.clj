(ns states.core
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.pprint :as pprint]))

(defrecord Property
  [commands next-step postcondition init-state])

(defrecord Variable
  [v]
  Object
  (toString [self]
    (pr-str self)))

(letfn [(method
          ([var] (pr var))
          ([var ^java.io.Writer w] (.write w (str "var-" (:v var)))))]
  (doseq [multi [print-method pprint/simple-dispatch]]
    (.addMethod ^clojure.lang.MultiFn multi Variable method)))

(doseq [sym '[->Property map->Property ->Variable map->Variable]]
  (alter-meta! (resolve sym) assoc :private :yes-please))

(defn- gen-command-with-state
  [{:keys [commands next-step]} state var]
  (gen/fmap #(vector % (next-step state var %))
            (commands state)))

(defn- gen-commands-from-state
  ([prop]
   (gen-commands-from-state prop (:init-state prop) 0))
  ([prop state var-count]
   (let [gen (gen-command-with-state prop state (->Variable var-count))]
     (gen/one-of
       [(gen/fmap #(subvec % 0 1) gen)
        (gen/bind
          gen
          (fn [[cmd state']]
            (gen/fmap (partial cons cmd)
                      (gen-commands-from-state prop
                                               state'
                                               (inc var-count)))))]))))

(defn- run-step
  [{:keys [next-step postcondition]} state step]
  (let [[_ var [f & args :as cmd]] step
        args-values (map #(get-in state [::vars (:v %)] %) args)
        val (apply (resolve f) args-values)
        state' (next-step state var cmd)]
    (if (postcondition state' cmd val)
      (update-in state' [::vars] conj val)
      (throw (ex-info "Postcondition unsatisfied"
                      (dissoc state ::vars))))))

(defn- prettify-commands
  "Turns a seq of commands into a seq of '(set var cmd) forms for improved
  readability of failure reports."
  [cmds]
  (map (fn [cmd n] (list 'set (->Variable n) (seq cmd)))
       cmds
       (range)))

(defn run-commands
  "Returns a test.check property. 4 required arguments are functions:

    - `commands` takes 1 argument: a current state. It returns a test.check
      generator of commands which can be executed in the given state. A command
      is a non-empty seq whose first argument is a resolvable symbol and others
      are variables passed to `next` or arbitrary values.
    - `next` takes 3 arguments: a current state, a variable to which the result
      of the command was bound and the executed command. It returns the next
      state.
    - `post` takes 3 arguments: a current state, the executed command and the
      value returned by the command. It returns a truthy value iff the property
      under test still holds.

  Optional arguments are passed as a map. Allowed keys are:

    - :init-state is the initial state."
  ([commands next post]
   (run-commands commands next post {}))
  ([commands next post {:keys [init-state] :or {init-state {}}}]
   (let [prop (->Property commands next post init-state)]
     (prop/for-all
       [cmds (gen/fmap prettify-commands (gen-commands-from-state prop))]
       (reduce (partial run-step prop)
               (assoc (:init-state prop) ::vars [])
               cmds)))))

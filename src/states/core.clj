(ns states.core
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.pprint :as pprint]))

(defrecord Property
  [commands precondition next-step postcondition init-state max-tries])

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
  [{:keys [commands next-step precondition max-tries]} state var]
  (->> (gen/such-that #(precondition state %) (commands state) max-tries)
       (gen/fmap #(vector % (next-step state var %)))))

(defn- gen-commands-from-state
  ([prop]
   (gen/bind (gen/fmap #(int (Math/sqrt %)) gen/nat)
             (partial gen-commands-from-state prop (:init-state prop) 0)))
  ([prop state var-count length]
   (let [gen (gen-command-with-state prop state (->Variable var-count))]
     (if-not (pos? length)
       (gen/fmap #(subvec % 0 1) gen)
       (gen/bind
         gen
         (fn [[cmd state']]
           (gen/fmap (partial cons cmd)
                     (gen-commands-from-state prop
                                              state'
                                              (inc var-count)
                                              (dec length)))))))))

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
    - `pre` takes 2 arguments: a current state and a command. It returns a
      truthy value iff the command is legal in the current state.
    - `next` takes 3 arguments: a current state, a variable to which the result
      of the command was bound and the executed command. It returns the next
      state.
    - `post` takes 3 arguments: a current state, the executed command and the
      value returned by the command. It returns a truthy value iff the property
      under test still holds.

  Optional arguments are passed as a map. Allowed keys are:

    - :init-state is the initial state.
    - :max-tries is the maximum number of times a generator returned by
      `commands` will be sampled until it returns a command satisfying `pre`.
      Increase this value if `pre` is unlikely to be satisfied."
  ([commands pre next post]
   (run-commands commands pre next post {}))
  ([commands pre next post {:keys [init-state max-tries]
                            :or {max-tries 10, init-state {}}}]
   (let [prop (->Property commands pre next post init-state max-tries)]
     (prop/for-all
       [cmds (gen/fmap prettify-commands (gen-commands-from-state prop))]
       (reduce (partial run-step prop)
               (assoc (:init-state prop) ::vars [])
               cmds)))))

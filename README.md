# States

Property-based generative testing for stateful computations in Clojure.

[![Build Status](https://travis-ci.org/jstepien/states.svg)](https://travis-ci.org/jstepien/states)

[![Clojars Project](http://clojars.org/states/latest-version.svg)](http://clojars.org/states)

## Example: `java.util.Set`

In this example we will specify interactions with objects implementing the `Set`
interface.
Let's start with requiring necessary namespaces.

```clojure
(require '[clojure.test.check :refer [quick-check]]
         '[clojure.test.check.generators :as gen]
         '[states.core :refer [run-commands]])
```

Commands which we are going to invoke on tested sets have to be identified using
resolvable symbols.
For that purpose we have to wrap the Java API with some vars.

```clojure
(defn set-contains [set elem]
  (.contains set elem))

(defn set-add [set elem]
  (.add set elem))

(defn set-remove [set elem]
  (.remove set elem))

(defn new-set [class]
  (.newInstance class))
```

The `commands` function returns a generator.
We assume that the `Set` under test is available in the state under key `:set`.
If that's not the case we return a command constructing it.
For the sake of simplicity we will work with sets containing exclusively
non-negative integers lesser than 10.

```clojure
(defn commands [{:keys [set class]}]
  (if set
    (gen/tuple (gen/elements `[set-contains set-add set-remove])
               (gen/return set)
               (gen/fmap #(mod % 10) gen/int))
    (gen/return [`new-set class])))
```

The next state of our computation is determined by `next-step`, in which we
manage a control set with all elements in the tested set and keep track of the
variable with the tested set.
If the command is `set-add` we conjoin the element to the set at `:elems`.
If the command is `new-set` we save the variable to which the test set is bound.

```clojure
(defn next-step [state var [fn _ elem]]
  (condp = fn
    `set-add (update-in state [:elems] conj elem)
    `new-set (assoc state :set var)
    state))
```

The last missing function is `postcondition` in which we validate that
`set-contains` returns `true` iff the element is in our control set at `:elems`.
For all other commands the postcondition is always satisfied.

```clojure
(defn postcondition [{:keys [elems]} [fn _ elem] value]
  (if (= fn `set-contains)
    (= value (contains? elems elem))
    true))
```

Finally, we build a property using `run-commands` and use `quick-check` to
verify that it holds.
We define the initial state to contain an empty `:elems` control set.
We decide to test `java.util.HashSet` by passing it as `:class`.

```clojure
(quick-check 1000 (run-commands commands next-step postcondition
                                {:init-state {:elems #{}
                                              :class java.util.HashSet}}))
;; => {:result
;;     #<ExceptionInfo clojure.lang.ExceptionInfo: Postcondition unsatisfied
;;       {:set var-0, :elems #{8}, :class java.util.HashSet}>,
;;     :seed 1414360515548,
;;     :failing-size 11,
;;     :num-tests 12,
;;     :fail
;;     [((set var-0 (user/new-set java.util.HashSet))
;;       (set var-1 (user/set-add var-0 8))
;;       (set var-2 (user/set-remove var-0 8))
;;       (set var-3 (user/set-contains var-0 8)))],
;;     :shrunk
;;     {:total-nodes-visited 15,
;;      :depth 0,
;;      :result
;;      #<ExceptionInfo clojure.lang.ExceptionInfo: Postcondition unsatisfied
;;        {:set var-0, :elems #{8}, :class java.util.HashSet}>,
;;      :smallest
;;      [((set var-0 (user/new-set java.util.HashSet))
;;        (set var-1 (user/set-add var-0 8))
;;        (set var-2 (user/set-remove var-0 8))
;;        (set var-3 (user/set-contains var-0 8)))]}}
```

It turns out that our postcondition doesn't hold if we test for presence of an
element which has been removed.
Why is it failing?
We misspecified `next-step` in our model of `java.util.Set`;
We forgot to remove elements from our control set after execution of
`set-remove`.
Let's correct this by adding another clause to `condp`.

```clojure
(defn next-step [state var [fn _ elem]]
  (condp = fn
    `set-remove (update-in state [:elems] disj elem)
    `set-add (update-in state [:elems] conj elem)
    `new-set (assoc state :set var)
    state))
```

Let's retest the property.

```clojure
(quick-check 1000 (run-commands commands next-step postcondition
                                {:init-state {:elems #{}
                                              :class java.util.HashSet}}))
;; => {:result true, :num-tests 100, :seed 1414360168056}
```

It works; The property is now satisfied.

## Related work

  - [QuviQ eqc](http://www.quviq.com/)

## License

    Copyright (c) 2014 Jan Stępień

    Permission is hereby granted, free of charge, to any person
    obtaining a copy of this software and associated documentation
    files (the "Software"), to deal in the Software without
    restriction, including without limitation the rights to use,
    copy, modify, merge, publish, distribute, sublicense, and/or
    sell copies of the Software, and to permit persons to whom the
    Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included
    in all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
    OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
    THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
    FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
    DEALINGS IN THE SOFTWARE.

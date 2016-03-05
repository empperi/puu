(ns puu.async-util
  (:require
    #?(:clj [clojure.core.async :refer [<!! take! timeout alts! go]])
    #?(:clj [clojure.test :refer [is]])
    #?(:cljs [cljs.core.async :refer [take! timeout alts!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]]
                            [cljs.test :refer [async is]])))


(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  #?(:clj
     (<!! ch)
     :cljs
     (async done
            (take! ch (fn [_] (done))))))

(defn test-within
  "Asserts that ch does not close or produce a value within ms. Returns a
  channel from which the value can be taken."
  [ms ch]
  (go (let [t (timeout ms)
            [v ch] (alts! [ch t])]
        (is (not= ch t)
            (str "Test should have finished within " ms "ms."))
        v)))

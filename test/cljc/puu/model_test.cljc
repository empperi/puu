(ns puu.model-test
  (:require [puu.model :as puu]
            [puu.async-util :refer [test-async test-within]]
            #?(:clj [clojure.core.async :refer [<! chan go]])
            #?(:cljs [cljs.core.async :refer [<! chan]])
            #?(:clj [clojure.test :refer :all])
            #?(:cljs [cljs.test :refer-macros [deftest is testing run-tests]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]]))
  #?(:clj (:import (java.util.concurrent Executors))))

(deftest manager-updates-its-model-and-its-versions
  (let [mgr (puu/manager "mgr-1" (puu/model {}))]
    (puu/do-tx mgr (fn [m] (assoc m :a 1)))
    (is (= {:a 1} @mgr))

    (puu/do-tx mgr (fn [m] (assoc m :b 2)))
    (is (= {:a 1 :b 2} @mgr))

    (puu/do-tx mgr (fn [m] (assoc m :c 3)))
    (is (= {:a 1 :b 2 :c 3} @mgr))))

(deftest manager-retains-old-versions
  (let [m   (puu/model {:value [1]})
        mgr (puu/manager "mgr-1" m)]
    (puu/do-tx mgr #(update % :value conj 2))
    (puu/do-tx mgr #(update % :value conj 3))
    (puu/do-tx mgr #(update % :value conj 4))

    (is (= [1 2 3 4] (:value @(puu/get-version mgr :latest))))
    (is (= [1 2 3] (:value @(puu/get-version mgr dec))))
    (is (= [1 2 3] (:value @(puu/get-version mgr 3))))
    (is (= [1 2] (:value @(puu/get-version mgr #(- % 2)))))
    (is (= [1 2] (:value @(puu/get-version mgr 2))))
    (is (= [1] (:value @(puu/get-version mgr #(- % 3)))))
    (is (= [1] (:value @(puu/get-version mgr 1))))
    (is (= nil @(puu/get-version mgr #(- % 4))))
    (is (= nil @(puu/get-version mgr inc)))))

#?(:clj
   (deftest manager-handles-concurrent-updates-correctly
     (let [tasks-n    10
           task-times 10
           execs      (* tasks-n task-times)
           pool       (Executors/newFixedThreadPool 20)
           mgr        (puu/manager "mgr-1" (puu/model {:value 0}))
           tx-counter (atom 0)
           tasks      (map (fn [n]
                             (fn []
                               (dotimes [x task-times]
                                 (puu/do-tx mgr (fn [data]
                                                  (swap! tx-counter inc)
                                                  (update data :value inc))))))
                           (range tasks-n))]
       (doseq [fut (.invokeAll pool tasks)]
         (.get fut))
       (.shutdown pool)

       ; there should have happened 'execs' additions by one to the :value
       (is (= {:value execs} @mgr))
       ; operation should have caused exactly 'execs' versions to be created
       (is (= (inc execs) (puu/version (puu/model-value mgr))))
       ; tx-counter should be a LOT higher number than version number since this test causes a lot of
       ; transaction collisions with extremely high contestion
       (is (> @tx-counter (puu/version (puu/model-value mgr))))
       ; history is intact for the latest 20 versions
       (doseq [v (range (- execs 20) execs)]
         (is (= {:value (dec v)} @(puu/get-version mgr v)))))))

(deftest map-format-of-model-contains-model-metadata-values
  (let [m (puu/model {:value [1 2]})]
    (is (= {:version 1 :timestamp (puu/timestamp m) :data {:value [1 2]}}
           (puu/model->map m)))))

(deftest calculate-changeset-for-version
  (let [mgr (puu/manager "mgr-1" (puu/model {:a 1}))]
    (puu/do-tx mgr (fn [data] (update data :a inc)))
    (puu/do-tx mgr (fn [data] (assoc-in data [:b] "b")))

    (is (= {:from    2
            :to      3
            :changes {:additions    {:b "b"}
                      :subtractions nil}}
           (puu/version-changes mgr 2 :latest)))
    (is (= {:from    1
            :to      3
            :changes {:additions    {:a 2 :b "b"}
                      :subtractions {:a 1}}}
           (puu/version-changes mgr 1 :latest)))))

(defn two-managers-test [m f]
  (let [mgr1 (puu/manager "mgr-1" (puu/model m))
        mgr2 (puu/manager "mgr-2" (puu/model m))]

    (f mgr1 mgr2)

    (puu/apply-changeset mgr2 (puu/version-changes mgr1 1 :latest))

    (is (= @(puu/get-version mgr1 :latest)
           @(puu/get-version mgr2 :latest)))))

(deftest applying-changeset-gives-identical-model-data-for-other-manager-with-same-base-model
  (testing "Simple new key"
    (two-managers-test {:a 1}
                       (fn [mgr1 _] (puu/do-tx mgr1 #(assoc % :b 2)))))

  (testing "Simple key value replacement"
    (two-managers-test
      {:a 1}
      (fn [mgr1 _] (puu/do-tx mgr1 #(assoc % :a 2)))))

  (testing "Simple vector addition"
    (two-managers-test
      {:data [1 2]}
      (fn [mgr1 _] (puu/do-tx mgr1 #(update % :data conj 3)))))

  (testing "Simple vector value removal"
    (two-managers-test
      {:data [1 2]}
      (fn [mgr1 _] (puu/do-tx mgr1 #(update % :data (comp vec drop-last))))))

  (testing "Several keys added in several versions"
    (two-managers-test
      {:a 1}
      (fn [mgr1 _]
        (puu/do-tx mgr1 #(assoc % :b 2))
        (puu/do-tx mgr1 #(assoc % :c 3))
        (puu/do-tx mgr1 #(assoc % :d 4)))))

  (testing "Complex data struture modification"
    (two-managers-test
      {:a [1 2] :b {:c "foo" :d 3}}
      (fn [mgr1 _]
        (puu/do-tx mgr1 #(update-in % [:b :d] (constantly 1)))
        (puu/do-tx mgr1 #(update-in % [:a 1] (constantly 10)))
        (puu/do-tx mgr1 #(assoc-in % [:b :x] {:foo "bar"})))))

  (testing "Both additions and removals in several versions"
    (two-managers-test
      {:a [1 2 3]}
      (fn [mgr1 _]
        (puu/do-tx mgr1 #(assoc-in % [:b] {:c [4 5 6]}))
        (puu/do-tx mgr1 #(update-in % [:b :c] conj 7))
        (puu/do-tx mgr1 #(update-in % [:b] (fn [m] (dissoc m :c))))))))

(deftest manager-changes-can-be-waited
  (testing "Single subscribed channel"
    (let [mgr1    (puu/manager "mgr-1" (puu/model {:a [1]}))
          in-chan (puu/subscribe mgr1 (chan))]
      (test-async
        (test-within 1000
                     (go
                       (puu/do-tx mgr1 #(update % :a conj 2))
                       (is (= {:a [1 2]} @(<! in-chan))))))))

  (testing "Several subscribed channels"
    (let [mgr1  (puu/manager "mgr-1" (puu/model {:a [1]}))
          chans [(puu/subscribe mgr1 (chan))
                 (puu/subscribe mgr1 (chan))
                 (puu/subscribe mgr1 (chan))]]
      (test-async
        (test-within 1000
                     (go
                       (puu/do-tx mgr1 #(update % :a conj 2))
                       (doseq [c chans]
                         (is (= {:a [1 2]} @(<! c))))))))))
(ns puu.core-test
  (:require [clojure.test :refer :all]
            [puu.core :refer :all])
  (:import (java.util.concurrent Executors)))


(deftest manager-updates-its-model-and-its-versions
  (let [mgr (manager "mgr-1" (model {}))]
    (do-tx mgr (fn [m] (assoc m :a 1)))
    (is (= {:a 1} @mgr))

    (do-tx mgr (fn [m] (assoc m :b 2)))
    (is (= {:a 1 :b 2} @mgr))

    (do-tx mgr (fn [m] (assoc m :c 3)))
    (is (= {:a 1 :b 2 :c 3} @mgr))))

(deftest manager-handles-concurrent-updates-correctly
  (let [pool (Executors/newFixedThreadPool 20)
        mgr (manager "mgr-1" (model {:value 0}))
        tx-counter (atom 0)
        tasks (map (fn [n]
                     (fn []
                       (dotimes [x 100]
                         (do-tx mgr (fn [data]
                                      (swap! tx-counter inc)
                                      (update data :value inc))))))
                   (range 100))]
    (doseq [fut (.invokeAll pool tasks)]
      (.get fut))
    (.shutdown pool)

    ; there should have happened 100*100 additions by one to the :value
    (is (= {:value (* 100 100)} @mgr))
    ; operation should have caused exactly 100*100 versions to be created
    (is (= (inc (* 100 100)) (version (model-value mgr))))
    ; tx-counter should be a LOT higher number than version number since this test causes a lot of
    ; transaction collisions with extremely high contestion
    (is (> @tx-counter (version (model-value mgr))))))

(deftest map-format-of-model-contains-model-metadata-values
  (let [m (model {:value [1 2]})]
    (is (= {:version 1 :timestamp (timestamp m) :data {:value [1 2]}}
           (model->map m)))))
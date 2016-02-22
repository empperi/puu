(ns puu.model-test
  (:require [clojure.test :refer :all]
            [puu.model :refer :all])
  (:import (java.util.concurrent Executors)))


(deftest manager-updates-its-model-and-its-versions
  (let [mgr (manager "mgr-1" (model {}))]
    (do-tx mgr (fn [m] (assoc m :a 1)))
    (is (= {:a 1} @mgr))

    (do-tx mgr (fn [m] (assoc m :b 2)))
    (is (= {:a 1 :b 2} @mgr))

    (do-tx mgr (fn [m] (assoc m :c 3)))
    (is (= {:a 1 :b 2 :c 3} @mgr))))

(deftest manager-retains-old-versions
  (let [m   (model {:value [1]})
        mgr (manager "mgr-1" m)]
    (do-tx mgr #(update % :value conj 2))
    (do-tx mgr #(update % :value conj 3))
    (do-tx mgr #(update % :value conj 4))

    (is (= [1 2 3 4] (:value @(get-version mgr :latest))))
    (is (= [1 2 3] (:value @(get-version mgr dec))))
    (is (= [1 2 3] (:value @(get-version mgr 3))))
    (is (= [1 2] (:value @(get-version mgr #(- % 2)))))
    (is (= [1 2] (:value @(get-version mgr 2))))
    (is (= [1] (:value @(get-version mgr #(- % 3)))))
    (is (= [1] (:value @(get-version mgr 1))))
    (is (= nil @(get-version mgr #(- % 4))))
    (is (= nil @(get-version mgr inc)))))

(deftest manager-handles-concurrent-updates-correctly
  (let [tasks-n    10
        task-times 10
        execs      (* tasks-n task-times)
        pool       (Executors/newFixedThreadPool 20)
        mgr        (manager "mgr-1" (model {:value 0}))
        tx-counter (atom 0)
        tasks      (map (fn [n]
                          (fn []
                            (dotimes [x task-times]
                              (do-tx mgr (fn [data]
                                           (swap! tx-counter inc)
                                           (update data :value inc))))))
                        (range tasks-n))]
    (doseq [fut (.invokeAll pool tasks)]
      (.get fut))
    (.shutdown pool)

    ; there should have happened 'execs' additions by one to the :value
    (is (= {:value execs} @mgr))
    ; operation should have caused exactly 'execs' versions to be created
    (is (= (inc execs) (version (model-value mgr))))
    ; tx-counter should be a LOT higher number than version number since this test causes a lot of
    ; transaction collisions with extremely high contestion
    (is (> @tx-counter (version (model-value mgr))))
    ; history is intact for the latest 20 versions
    (doseq [v (range (- execs 20) execs)]
      (is (= {:value (dec v)} @(get-version mgr v))))))

(deftest map-format-of-model-contains-model-metadata-values
  (let [m (model {:value [1 2]})]
    (is (= {:version 1 :timestamp (timestamp m) :data {:value [1 2]}}
           (model->map m)))))

(deftest calculate-changeset-for-version
  (let [mgr (manager "mgr-1" (model {:a 1}))]
    (do-tx mgr (fn [data] (update data :a inc)))
    (do-tx mgr (fn [data] (assoc-in data [:b] "b")))

    (is (= {:from    2
            :to      3
            :changes {:additions    {:b "b"}
                      :subtractions nil}}
           (version-changes mgr 2 :latest)))
    (is (= {:from    1
            :to      3
            :changes {:additions    {:a 2 :b "b"}
                      :subtractions {:a 1}}}
           (version-changes mgr 1 :latest)))))

(defn two-managers-test [m f]
  (let [mgr1 (manager "mgr-1" (model m))
        mgr2 (manager "mgr-2" (model m))]

    (f mgr1 mgr2)

    (apply-changeset mgr2 (version-changes mgr1 1 :latest))

    (is (= @(get-version mgr1 :latest)
           @(get-version mgr2 :latest)))))

(deftest applying-changeset-gives-identical-model-data-for-other-manager-with-same-base-model
  (testing "Simple new key"
    (two-managers-test {:a 1}
      (fn [mgr1 _] (do-tx mgr1 #(assoc % :b 2)))))

  (testing "Simple key value replacement"
    (two-managers-test
      {:a 1}
      (fn [mgr1 _] (do-tx mgr1 #(assoc % :a 2)))))

  (testing "Simple vector addition"
    (two-managers-test
      {:data [1 2]}
      (fn [mgr1 _] (do-tx mgr1 #(update % :data conj 3)))))

  (testing "Simple vector value removal"
    (two-managers-test
      {:data [1 2]}
      (fn [mgr1 _] (do-tx mgr1 #(update % :data (comp vec drop-last))))))

  (testing "Several keys added in several versions"
    (two-managers-test
      {:a 1}
      (fn [mgr1 _]
        (do-tx mgr1 #(assoc % :b 2))
        (do-tx mgr1 #(assoc % :c 3))
        (do-tx mgr1 #(assoc % :d 4)))))

  (testing "Complex data struture modification"
    (two-managers-test
      {:a [1 2] :b {:c "foo" :d 3}}
      (fn [mgr1 _]
        (do-tx mgr1 #(update-in % [:b :d] (constantly 1)))
        (do-tx mgr1 #(update-in % [:a 1] (constantly 10)))
        (do-tx mgr1 #(assoc-in % [:b :x] {:foo "bar"})))))

  (testing "Both additions and removals in several versions"
    (two-managers-test
      {:a [1 2 3]}
      (fn [mgr1 _]
        (do-tx mgr1 #(assoc-in % [:b] {:c [4 5 6]}))
        (do-tx mgr1 #(update-in % [:b :c] conj 7))
        (do-tx mgr1 #(update-in % [:b] (fn [m] (dissoc m :c))))))))

(deftest manager-changes-can-be-waited
  (testing "Single waiting future"
    (let [mgr1 (manager "mgr-1" (model {:a [1]}))
          fut  (wait mgr1 "waiting-1")]
      ; give wait future some time to do it's magic
      (Thread/sleep 50)
      (is (= false (realized? fut)))
      (do-tx mgr1 #(update % :a conj 2))
      (is (= {:a [1 2]} @(deref fut 500 (atom "timeout"))))))

  (testing "Several waiting futures"
    (let [mgr1 (manager "mgr-1" (model {:a [1]}))
          futs  [(wait mgr1 "waiting-1")
                 (wait mgr1 "waiting-2")
                 (wait mgr1 "waiting-3")]]
      ; give wait futures some time to do it's magic
      (Thread/sleep 50)
      (is (= true (every? (complement realized?) futs)))
      (do-tx mgr1 #(update % :a conj 2))
      (doseq [fut futs]
        (is (= {:a [1 2]} @(deref fut 500 (atom "timeout"))))))))
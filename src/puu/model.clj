(ns puu.model
  (:require [clojure.data :as d]
            [clojure.set :as sets])
  (:import (clojure.lang Ref IDeref IFn Keyword)
           (java.lang.ref SoftReference)
           (java.util.concurrent LinkedTransferQueue)))

(defprotocol Version
  (version [_])
  (timestamp [_])
  (prev [_]))

(deftype PuuModel [ver ts data ^SoftReference prev]
  IDeref Version
  (deref [_] data)
  (version [_] ver)
  (timestamp [_] ts)
  (prev [_] (when (some? prev) (.get prev))))

(defprotocol IManager
  (previous [_])
  (ref-access [_])
  (model-value [_])
  (mgr-name [_])
  (tx-wait [_ wait-queue-name])
  (tx-deliver [_ m]))

(deftype Manager [mgr-name ^Ref d tx-queues lock]
  IDeref IManager
  (deref [_] @@d)
  (model-value [_] @d)
  (ref-access [_] d)
  (mgr-name [_] mgr-name)
  (previous [_] (Manager. mgr-name (ref (prev @d)) (ref {}) (Object.)))
  (tx-wait [_ wait-queue-name]
    (letfn [(get-queue []
              (dosync
                (let [queue (or (get @tx-queues wait-queue-name)
                                (get (alter tx-queues assoc-in [wait-queue-name] {:timestamp (System/currentTimeMillis)
                                                                                  :queue     (LinkedTransferQueue.)})
                                     wait-queue-name))]
                  (alter tx-queues assoc-in [wait-queue-name :timestamp] (System/currentTimeMillis))
                  (:queue queue))))]
      ; we really don't want to block inside a transaction...
      (.take (get-queue))))
  (tx-deliver [_ m]
    ; ok wtf? locking AND dosync? U mad bro?
    ; ...well, actually no. The thing is, we are using LinkedTransferQueue as our queue implementation (see above).
    ; that Queue implementation is mutable and thus it works not so well with Clojure STM when transaction fails.
    ; While with persistent data structures this is not a problem since all changes made are undoed naturally since
    ; the new versions don't have references and GC can clean them up, but with mutable collections this is very much
    ; not the case. Also, in this scenario it would be really awkward to take the .put operation out of the transaction
    ; since then we'd might end up having all kinds of strange situations. Also, even if we forget the fact that
    ; clojure.lang.PersistentQueue doesn't support .hasWaitingConsumer or anything similar, we might end up into
    ; scenario where we'd put data into PersistentQueue, then the waiting thread for queue data gets it and goes to
    ; do it's work and then the transaction fails. Then that same data is added again to the queue and boom - we got
    ; same data twice into our queue which is NOT what we want.
    ;
    ; Thus, only way to be sure here is to use locking. And since we have (and need) refs we need to use dosync. Hope
    ; this clarifies things and proves that I'm not insane for writing this abomination here.
    (locking lock
      (dosync
        (doseq [[q-name queue] @tx-queues]
          (when (.hasWaitingConsumer (:queue queue))
            (.put (:queue queue) m)
            (alter tx-queues assoc-in [q-name :timestamp] (System/currentTimeMillis)))
          ; remove over 1 second old queues with no activity
          (when (< 1000 (- (System/currentTimeMillis) (:timestamp queue)))
            (alter tx-queues dissoc q-name)))))))

(defn- new-version [^PuuModel m data]
  (PuuModel. (inc (version m)) (System/currentTimeMillis) data (SoftReference. m)))

(defn- seqzip
  "returns a sequence of [[ value-left] [value-right]....]  padding with nulls for shorter sequences "
  [left right]
  (loop [list [] a left b right]
    (if (or (seq a) (seq b))
      (recur (conj list [(first a) (first b)] ) (rest a) (rest b))
      list)))

(defn- recursive-diff-merge
  " Merge two structures recusively , taking non-nil values from sequences and maps and merging sets"
  [part-state original-state]
  (cond
    (sequential? part-state) (map (fn [[l r]] (recursive-diff-merge l r)) (seqzip part-state original-state))
    (map? part-state) (merge-with recursive-diff-merge part-state original-state)
    (set? part-state) (sets/union part-state original-state)
    (nil? part-state ) original-state
    :default part-state))

(defn- undiff
  "returns the state of x after reversing the changes described by a diff against
   an earlier state (where before and after are the first two elements of the diff)"
  [x before after]
  (let [[a _ _] (d/diff x after)]
    (recursive-diff-merge a before)))

(defn model [m]
  (PuuModel. 1 (System/currentTimeMillis) m nil))

(defn model->map [^PuuModel m]
  {:data      @m
   :version   (version m)
   :timestamp (timestamp m)})

(defn manager [mgr-name ^PuuModel m]
  (Manager. mgr-name (ref m :min-history 5 :max-history 100) (ref {}) (Object.)))

(defn do-tx [^Manager m f]
  (dosync
    (alter
      (ref-access m)
      (fn [data]
        (let [v (new-version data (f @data))]
          ; delivering results in another thread so we step out of this dosync block
          (future (tx-deliver m v))
          v)))))

(defn get-version-by-num [mgr version-num]
  (if-let [cur-version (model-value mgr)]
    (if (= version-num (version cur-version))
      cur-version
      (recur (previous mgr) version-num))
    (atom nil)))

(defmulti get-version (fn [mgr version-delta] (type version-delta)))

(defmethod get-version IFn [mgr version-delta]
  (get-version-by-num mgr (version-delta (version (model-value mgr)))))

(defmethod get-version Number [mgr version-delta]
  (get-version-by-num mgr version-delta))

(defmethod get-version Keyword [mgr version-delta]
  (condp = version-delta
    :latest (model-value mgr)
    :first nil
    nil))

(defmethod get-version nil [_ _] nil)

(defmethod get-version :default [mgr version-delta]
  (throw (ex-info
           (format "Unknown version-delta type: %s" (type version-delta))
           {:type (type version-delta)})))

(defn version-changes [mgr from to]
  (let [from-v (get-version mgr from)
        to-v   (get-version mgr to)
        diff   (d/diff @from-v @to-v)]
    {:from    (version from-v)
     :to      (version to-v)
     :changes {:additions    (second diff)
               :subtractions (first diff)}}))

(defn apply-changeset [mgr diff]
  (do-tx
    mgr
    (fn [data]
      (undiff data (-> diff :changes :additions) (-> diff :changes :subtractions)))))

(defn wait [mgr wait-queue-name]
  (future
    (tx-wait mgr wait-queue-name)))
(ns puu.model
  (:require [clojure.data :as d]
            [clojure.set :as sets]
            #?(:clj [clojure.core.async :refer [>! <! >!! <!! go chan mult tap untap sliding-buffer]])
            #?(:cljs [cljs.core.async :refer [>! <! chan mult tap untap sliding-buffer]])
            #?(:cljs [cljs.core :refer [IDeref IFn Keyword]]))
  (:import #?(:clj (clojure.lang IDeref IFn Keyword))
           #?(:clj (java.lang.ref SoftReference)))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]])))

(defn- time-millis []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defprotocol Version
  (version [_])
  (timestamp [_])
  (prev [_]))

(deftype PuuModel [ver ts data prev]
  IDeref
  #?(:clj (deref [_] data)
     :cljs (-deref [_] data))

  Version
  (version [_] ver)
  (timestamp [_] ts)
  (prev [_] (when (some? prev)
              #?(:clj (.get prev)
                 :cljs prev))))

(defprotocol IManager
  (previous [_])
  (ref-access [_])
  (model-value [_])
  (mgr-name [_])
  (subscribe [_ c])
  (unsubscribe [_ c])
  (publish [_ m]))

(deftype Manager [mgr-name d in-chan mult-chan]
  IDeref
  #?(:clj (deref [_] @@d)
     :cljs (-deref [_] @@d))

  IManager
  (model-value [_] @d)
  (ref-access [_] d)
  (mgr-name [_] mgr-name)
  (previous [_] (let [in (chan (sliding-buffer 50))]
                  (Manager. mgr-name
                            #?(:clj (ref (prev @d))
                               :cljs (atom (prev @d)))
                            in
                            (mult in))))
  (subscribe [_ c]
    (tap mult-chan c))
  (unsubscribe [_ c]
    (untap mult-chan c))
  (publish [_ m]
    (when (some? m)
      (go (>! in-chan m)))))

(defn- new-version [^PuuModel m data]
  (PuuModel. (inc (version m)) (time-millis) data
             #?(:clj (SoftReference. m)
                :cljs m)))

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
  (PuuModel. 1 (time-millis) m nil))

(defn model->map [^PuuModel m]
  {:data      @m
   :version   (version m)
   :timestamp (timestamp m)})

(defn manager [mgr-name ^PuuModel m]
  (let [in-chan (chan (sliding-buffer 50))]
    (Manager. mgr-name
              #?(:clj (ref m :min-history 5 :max-history 100)
                 :cljs (atom m))
              in-chan
              (mult in-chan))))

(defn do-tx [^Manager m f]
  #?(:clj (dosync
            (alter
              (ref-access m)
              (fn [data]
                (let [v (new-version data (f @data))]
                  (publish m v)
                  v))))
     :cljs (swap!
             (ref-access m)
             (fn [data]
               (let [v (new-version data (f @data))]
                  (publish m v)
                  v)))))

(defn get-version-by-num [mgr version-num]
  (if-let [cur-version (model-value mgr)]
    (if (= version-num (version cur-version))
      cur-version
      (recur (previous mgr) version-num))
    (atom nil)))

(defn get-version [mgr version-delta]
  (cond
    (number? version-delta) (get-version-by-num mgr version-delta)
    (fn? version-delta) (get-version-by-num mgr (version-delta (version (model-value mgr))))
    (= :latest version-delta) (model-value mgr)
    :default (ex-info
               (str "Unknown version-delta type: " (type version-delta))
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
(ns puu.core
  (:require [clojure.data :as d])
  (:import (clojure.lang Ref)))

(defprotocol Version
  (version [_])
  (timestamp [_]))

(deftype PuuModel [ver ts data]
  clojure.lang.IDeref Version
  (deref [_] data)
  (version [_] ver)
  (timestamp [_] ts))

(defprotocol ManagerRefAccess
  (ref-access [_])
  (model-value [_])
  (mgr-name [_]))

(deftype Manager [mgr-name ^Ref d]
  clojure.lang.IDeref ManagerRefAccess
  (deref [_] @@d)
  (model-value [_] @d)
  (ref-access [_] d)
  (mgr-name [_] mgr-name))


(defn- new-version [^PuuModel m data]
  (PuuModel. (inc (version m)) (System/currentTimeMillis) data))


(defn model [m]
  (PuuModel. 1 (System/currentTimeMillis) m))

(defn model->map [^PuuModel m]
  {:data @m
   :version (version m)
   :timestamp (timestamp m)})

(defn manager [mgr-name ^PuuModel m]
  (Manager. mgr-name (ref m :min-history 50 :max-history 100)))

(defn do-tx [^Manager m f]
  (dosync
    (alter
      (ref-access m)
      (fn [data]
        (new-version data (f @data))))))
; Auto-generated by Boot, regenerate by executing: 'boot lein-generate'
(defproject
  collin/puu
  "0.2.0-SNAPSHOT"
  :dependencies
  [[org.clojure/clojure "1.8.0"]
   [org.clojure/clojurescript "1.7.228"]
   [org.clojure/core.async "0.2.374"]
   [amalloy/ring-buffer "1.2"]
   [adzerk/boot-test "1.1.0" :scope "test"]
   [adzerk/boot-cljs "1.7.228-1" :scope "test"]
   [crisptrutski/boot-cljs-test "0.2.2-SNAPSHOT" :scope "test"]
   [instaparse "1.4.1" :scope "test"]]
  :source-paths
  ["src/cljs" "src/cljc" "src/clj"]
  :test-paths
  ["test/clj" "test/cljs" "test/cljc"])
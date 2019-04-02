(defproject cucumber-clojure "4.2.6-SNAPSHOT"
  :source-paths ["src/main/clojure"]
  :test-paths ["src/test/clojure"]
  :resource-paths ["src/test/resources"]
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [io.cucumber/cucumber-core "4.2.6"]]
  :aot [cucumber.runtime.clj]
  :main cucumber.runner)

(use 'cucumber.runtime.clojure.belly)

(def some-state (atom "'Before' hasn't run."))

(Before []
  (reset! some-state "'Before' has run.")
  (println "Executing 'Before'."))

(Before ["@foo"]
  (println "Executing 'Tagged Before'"))

(After []
  (println (str "Executing 'After' " @some-state)))

(Given "I have {int} cukes in my belly" [cuke-count]
  (eat cuke-count))

(Given "I have this many cukes in my belly:" [cuke-table]
  (doseq [x (seq (.asList cuke-table Long))] (eat x)))

(When "there are {int} cukes in my belly" [expected]
  (assert (= (last-meal) expected)))

(Given "{int} unimplemented step" [arg1]
  (comment  Express the Regexp above with the code you wish you had  )
  (throw (cucumber.api.PendingException. "This is pending. Seeing a stacktrace here is normal.")))

(def most-recent (atom nil))

(Given "I have a kv table:" [data]
  (reset! most-recent (kv-table->map data)))

(Given "I have a table with its keys in a header row:" [data]
  (reset! most-recent (table->rows data)))

(Then "the clojure literal equivalent should be:" [literal-as-string]
  (assert (= @most-recent (read-string literal-as-string))))

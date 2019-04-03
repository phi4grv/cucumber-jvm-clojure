(ns cucumber.runtime.clj
  (:require (clojure [string :as str]))
  (:import (cucumber.runtime CucumberException
                             HookDefinition
                             StepDefinition)
           (cucumber.runtime.filter TagPredicate)
           (cucumber.runtime.snippets Snippet
                                      SnippetGenerator)
           (io.cucumber.core.model Classpath)
           (io.cucumber.cucumberexpressions CaptureGroupTransformer
                                            ParameterType)
           (io.cucumber.stepexpression ExpressionArgumentMatcher
                                       StepExpressionFactory)
           (clojure.lang RT)
           (java.lang.reflect Type)
           (java.util ArrayList))
  (:gen-class :name cucumber.runtime.clj.Backend
              :implements [cucumber.runtime.Backend]
              :constructors
              {[cucumber.runtime.io.ResourceLoader io.cucumber.stepexpression.TypeRegistry] []}
              :init init
              :state state))

(def glue (atom nil))
(def type-registry (atom nil))

(defn clojure-snippet []
  (reify
    Snippet
    (template [_]
      (str
       "({0} \"{1}\" [{3}]\n"
       "  (comment {4})\n"
       "  (throw (cucumber.api.PendingException.)))\n"))
    (arguments [_ argumentTypes]
      (->> argumentTypes
           (map-indexed (fn [i _] (str "arg" i)))
           (str/join " ")))
    (tableHint [_] nil)
    (escapePattern [_ pattern]
      (str/replace (str pattern) "\"" "\\\""))))

(defn load-script [path]
  (try
    (RT/load (str/replace path #"\.clj$" "") true)
    (catch Throwable t
      (throw (CucumberException. t)))))

(defn- -init [resource-loader a-type-registry]
  (reset! type-registry a-type-registry)
  (let [snippet-generator (->> a-type-registry
                               .parameterTypeRegistry
                               (SnippetGenerator. (clojure-snippet)))]
    [[] (atom {:resource-loader resource-loader
               :snippet-generator snippet-generator})]))

(defn -loadGlue [cljb a-glue glue-paths]
  (reset! glue a-glue)
  (doseq [path glue-paths
          resource (.resources (:resource-loader @(.state cljb)) path ".clj")]
    (binding [*ns* (create-ns 'cucumber.runtime.clj)]
      (load-script (-> resource .getPath Classpath/resourceName)))))

(defn- -buildWorld [cljb])

(defn- -disposeWorld [cljb])

(defn- -getSnippet [cljb step keyword _]
  (.getSnippet (:snippet-generator @(.state cljb)) step keyword nil))

(defn- -setUnreportedStepExecutor [cljb executor]
  "executor")

(defn- location-str [{:keys [file line]}]
  (str file ":" line))

(defn add-step-definition [pattern fun location]
  (.addStepDefinition
   @glue
   (reify
     StepDefinition
      (matchedArguments [_ step]
        (-> @type-registry
            StepExpressionFactory.
            (.createExpression (str pattern))
            ExpressionArgumentMatcher.
            (.argumentsFrom step (into-array Type []))))
     (getLocation [_ detail]
       (location-str location))
     (getParameterCount [_]
       nil)
     (execute [_ args]
       (apply fun args))
     (isDefinedAt [_ stack-trace-element]
       (and (= (.getLineNumber stack-trace-element)
               (:line location))
            (= (.getFileName stack-trace-element)
               (:file location))))
     (getPattern [_]
       (str pattern))
     (isScenarioScoped [_]
       false))))

(defmulti add-hook-definition (fn [t & _] t))

(defmethod add-hook-definition :before [_ tag-expression hook-fun location]
  (let [tp (TagPredicate. tag-expression)]
    (.addBeforeHook
     @glue
     (reify
       HookDefinition
       (getLocation [_ detail?]
         (location-str location))
       (execute [hd scenario-result]
         (hook-fun))
       (matches [hd tags]
         (.apply tp tags))
       (getOrder [hd] 0)
       (isScenarioScoped [hd] false)))))

(defmethod add-hook-definition :after [_ tag-expression hook-fun location]
  (let [tp (TagPredicate. tag-expression)
        max-parameter-count (->> hook-fun class .getDeclaredMethods
                                 (filter #(= "invoke" (.getName %)))
                                 (map #(count (.getParameterTypes %)))
                                 (apply max))]
    (.addAfterHook
     @glue
     (reify
       HookDefinition
       (getLocation [_ detail?]
         (location-str location))
       (execute [hd scenario-result]
         (if (zero? max-parameter-count)
           (hook-fun)
           (hook-fun scenario-result)))
       (matches [hd tags]
         (.apply tp tags))
       (getOrder [hd] 0)
       (isScenarioScoped [hd] false)))))

(defmacro step-macros [& names]
  (cons 'do
        (for [name names]
          `(defmacro ~name [pattern# binding-form# & body#]
             `(add-step-definition ~pattern#
                                   (fn ~binding-form# ~@body#)
                                   '~{:file *file*
                                      :line (:line (meta ~'&form))})))))
(step-macros
 Given When Then And But)

(defn- hook-location [file form]
  {:file file
   :line (:line (meta form))})

(defmacro Before [tags & body]
  `(add-hook-definition :before ~tags (fn [] ~@body) ~(hook-location *file* &form)))

(defmacro After [tags & body]
  `(add-hook-definition :after ~tags (fn [] ~@body) ~(hook-location *file* &form)))

(defn ^:private update-keys [f m]
  (reduce-kv #(assoc %1 (f %2) %3) {} m))

(defn ^:private update-values [f m]
  (reduce-kv #(assoc %1 %2 (f %3)) {} m))

(defn read-cuke-str
  "Using the clojure reader is often a good way to interpret literal values
   in feature files. This function makes some cucumber-specific adjustments
   to basic reader behavior. This is particulary appropriate when reading a
   table, for example: reading | \"1\" | 1 | we should intepret 1 as an int
   and \"1\" as a string. This is used by kv-table->map and table->rows."
  [string]
  (if (re-matches #"^:.*|\d+(\.\d+)?" string)
    (read-string string)
    (str/replace string #"\"" "")))

(defn kv-table->map
  "Reads a table of the form  | key | value |
   For example, given:
     | from      | 1293884100000 |
     | to        | 1293884100000 |
   It evaluates to the clojure literal:
     {:from 1293884100000, :to 1293884100000}"
  [data]
  (->> (into {} (map vec (.asLists data)))
       (update-values read-cuke-str)
       (update-keys keyword)))

(defn table->rows
  "Reads a cucumber table of the form
     | key-1 | key-2 | ... | key-n |
     | val-1 | val-2 | ... | val-n |
   For example, given:
     | id | name    | created-at    |
     | 55 | \"foo\" | 1293884100000 |
     | 56 | \"bar\" | 1293884100000 |
   It evaluates to the clojure literal:
     [{:id 55, :name \"foo\", :created-at 1293884100000}
      {:id 56, :name \"bar\", :created-at 1293884100000}]"
  [data]
  (let [data (map seq (.asLists data))
        header-keys (map keyword (first data))
        remove-blank (fn [m,k,v] (if (seq (str v)) (assoc m k v) m))
        row->hash (fn [row] (apply hash-map
                                   (interleave header-keys
                                               (map read-cuke-str row))))]
    (map (fn [row-vals] (reduce-kv remove-blank {} (row->hash row-vals)))
         (next data))))

(defn define-parameter-type
  ([name regexps transformer]
   (define-parameter-type name regexps transformer {}))
  ([name regexps transformer
    {:keys [use-for-snippets prefer-for-regexp-match]
     :or {use-for-snippets true prefer-for-regexp-match false}}]
   (let [patterns (cond->> regexps
                    (not (sequential? regexps)) vector
                    true (into [] (map str)))
        tf-fn (reify CaptureGroupTransformer
                (transform [this args]
                  (transformer args)))]
    (.defineParameterType @type-registry
                          (ParameterType.
                            name
                            (java.util.ArrayList. patterns)
                            (class Object)
                            tf-fn
                            use-for-snippets
                            prefer-for-regexp-match)))))

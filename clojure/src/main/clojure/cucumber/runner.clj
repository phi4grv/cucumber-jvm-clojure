(ns cucumber.runner
  (:import cucumber.api.cli.Main))

(defn run-with-cli-args
  ([args]
   (->> (Thread/currentThread)
        .getContextClassLoader
        (run-with-cli-args args)))
  ([args classloader]
   (as-> args $
     (into-array String $)
     (cucumber.api.cli.Main/run $ classloader))))

(defn run [opts paths]
  (let [opt->str (fn [[k v]]
                   (let [optkey (->> k name (str "--"))]
                     (cond
                       (boolean? v) [optkey]
                       (sequential? v) (into []
                                             (comp
                                               (map (fn [x] [optkey (str x)]))
                                               cat)
                                             v)
                       :else [optkey (str v)])))
        sopts (into []
                    (comp (map opt->str) cat)
                    opts)
        args (concat sopts paths)
        ret (run-with-cli-args args)]
    (if (= ret 0) ::ok ::failure)))

(defn -main [& args]
  (run-with-cli-args args))

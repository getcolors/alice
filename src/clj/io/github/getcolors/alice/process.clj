(ns io.github.getcolors.alice.process
  "Application subprocesses do not inherit provider or SSH resource secrets."
  (:require [green.process :as process]))
(def posix-quote process/posix-quote)
(defn- safe-options [opts]
  (update opts :extra-env #(merge (process/secret-env-removals) %)))
(defn run
  ([args] (run args {}))
  ([args opts] (process/run args (safe-options opts))))
(defn run-inherit
  ([args] (run-inherit args {}))
  ([args opts] (process/run-inherit args (safe-options opts))))
(defn run-with-timeout [args opts timeout]
  (process/run-with-timeout args (safe-options opts) timeout))

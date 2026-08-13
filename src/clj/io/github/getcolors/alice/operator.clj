(ns io.github.getcolors.alice.operator
  "Foreground SSH tunnel dispatch for the private Transmission UI."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [green.cli :as green-cli]
            [green.process :as process]
            [io.github.getcolors.alice.utils :as utils]
            [io.github.getcolors.alice.validate :as validate]))

(defn command [opts local-port]
  ["ssh" "-o" "IgnoreUnknown=UseKeychain"
   "-F" (utils/ssh-config-path)
   "-o" "ExitOnForwardFailure=yes"
   "-N" "-L" (format "127.0.0.1:%d:127.0.0.1:%d"
                       local-port (or (:transmission-rpc-port opts) 9091))
   "--" (utils/host-alias opts)])

(defn- parse-port [x default]
  (let [port (if x (parse-long (str x)) default)]
    (when (and port (<= 1 port 65535)) port)))

(defn run
  ([state-file args] (run state-file args process/run-inherit (System/getenv)))
  ([state-file args runner env]
   (try
     (let [file (io/file state-file)]
       (cond
         (not (.exists file))
         {:green/exit 2 :green/err (str "desired state file not found: " file)}

         (> (count args) 1)
         {:green/exit 2 :green/err "tunnel accepts at most one local port"}

         :else
         (let [opts (-> (green-cli/read-state file (slurp file))
                        (assoc :green/state-file (.getAbsolutePath file))
                        (green-cli/read-pars env))
               errors (validate/env-errors env)
               local-port (parse-port (first args)
                                      (or (:transmission-tunnel-local-port opts)
                                          19091))]
           (cond
             (seq errors) {:green/exit 2 :green/err (str/join "\n" errors)}
             (nil? local-port) {:green/exit 2 :green/err "local port must be from 1 to 65535"}
             :else (let [{:keys [exit err]} (runner (command opts local-port))]
                     (cond-> {:green/exit (if (zero? exit) 0 (max 1 exit))}
                       (and (not (zero? exit)) (not-empty err))
                       (assoc :green/err err)))))))
     (catch Throwable t
       {:green/exit 2 :green/err (or (ex-message t) (str (class t)))}))))

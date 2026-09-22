(ns io.github.getcolors.alice.ssh
  "Application SSH arguments; colors-compute owns key lifecycle."
  (:require [clojure.java.io :as io] [io.github.getcolors.alice.compute :as compute]))
(def build-placeholder-dir "/home/build-placeholder/.ssh")
(defn rendered-only? [opts] (or (= :build (:green/event opts)) (boolean (:green/dry-run opts))))
(defn with-machine-key [opts]
  (let [path (if (rendered-only? opts) (compute/placeholder-key opts) (:ssh-private-key-path opts))]
    (cond-> opts path (assoc :ssh-private-key-path path :ssh-public-key-path (str path ".pub")))))
(defn identity-args [opts]
  (if-let [path (:ssh-private-key-path opts)]
    ["-i" path "-o" "IdentitiesOnly=yes" "-o" (str "IdentityAgent=" (or (:alice/agent-socket opts) "none"))
     "-o" "ForwardAgent=no"] []))
(defn direct-args [opts]
  (into (identity-args opts) ["-o" "ControlMaster=no" "-o" "ControlPersist=no" "-S" "none"]))
(defn private-key-path [opts]
  (when-not (:ssh-private-key-path opts) (throw (ex-info "deployment SSH identity unavailable" {})))
  (.getAbsolutePath (io/file (:ssh-private-key-path opts))))

(defn keygen? [_] true)
(defn public-key-path [opts] (str (private-key-path opts) ".pub"))

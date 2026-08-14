(ns io.github.getcolors.alice.utils
  "Launcher contract and package path helpers."
  (:require [green.cli :as green-cli]))

(def contract 2)

(defn tool-dir [opts tool]
  (green-cli/stage-dir opts tool {:default-profile "alice"}))

(defn host-alias [opts]
  (or (not-empty (str (:profile opts))) "alice"))

(defn ssh-config-path []
  (str (java.io.File. (System/getProperty "user.home") ".ssh/config")))

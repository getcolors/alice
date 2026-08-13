(ns io.github.getcolors.alice.describe
  "Non-mutating local SSH and Transmission status reporting."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [green.process :as process]
            [io.github.getcolors.alice.utils :as utils]))

(defn- host-block [config alias]
  (when (and (not (str/blank? config)) (not (str/blank? alias)))
    (second
     (re-find
      (re-pattern
       (str "(?ms)^\\s*Host\\s+" (java.util.regex.Pattern/quote alias)
            "\\s*$\\R(.*?)(?=^\\s*Host\\s+|\\z)"))
      config))))

(defn- host-name [block]
  (some->> block (re-find #"(?mi)^\s*HostName\s+(\S+)\s*$") second))

(defn- default-read-config []
  (let [file (io/file (utils/ssh-config-path))]
    (when (.exists file) (slurp file))))

(defn- default-runner [args]
  (process/run-with-timeout args {} 15000))

(defn describe-report
  ([opts] (describe-report opts default-runner default-read-config))
  ([opts runner read-config]
   (let [alias (utils/host-alias opts)
         block (host-block (or (read-config) "") alias)
         ssh-result (when block
                      (runner ["ssh" "-o" "IgnoreUnknown=UseKeychain"
                               "-F" (utils/ssh-config-path)
                               "-o" "BatchMode=yes" "-o" "ConnectTimeout=5"
                               "--" alias "true"]))
         reachable? (and ssh-result (zero? (:exit ssh-result)))
         service-result (when reachable?
                          (runner ["ssh" "-o" "IgnoreUnknown=UseKeychain"
                                   "-F" (utils/ssh-config-path)
                                   "--" alias "systemctl" "is-active"
                                   "transmission-daemon"]))
         service (some-> (:out service-result) str/trim)]
     {:profile (:profile opts)
      :droplet (select-keys opts [:digitalocean-name :digitalocean-region
                                  :digitalocean-size :digitalocean-image
                                  :digitalocean-vpc-uuid
                                  :digitalocean-ssh-keys])
      :ssh {:alias alias :config (utils/ssh-config-path)
            :present? (boolean block) :host (host-name block)
            :reachable? (boolean reachable?)}
      :transmission {:active? (= "active" service)
                     :status (or (not-empty service)
                                 (if reachable? "unknown" "not checked"))}
      :tunnel {:local-port (:transmission-tunnel-local-port opts)
               :remote-port (:transmission-rpc-port opts)}})))

(defn print-report [{:keys [profile droplet ssh transmission tunnel]}]
  (println (str "Profile: " profile))
  (println (format "DigitalOcean: %s · %s · %s · %s"
                   (:digitalocean-name droplet) (:digitalocean-region droplet)
                   (:digitalocean-size droplet) (:digitalocean-image droplet)))
  (println (format "SSH: alias=%s configured=%s host=%s reachable=%s"
                   (:alias ssh) (:present? ssh) (or (:host ssh) "unknown")
                   (:reachable? ssh)))
  (println (format "Transmission: %s" (:status transmission)))
  (println (format "Tunnel: ./green tunnel %s, then open http://127.0.0.1:%s/transmission/web/"
                   (:local-port tunnel) (:local-port tunnel))))

(defn describe-step [opts]
  (let [report (describe-report opts)]
    (print-report report)
    (assoc opts :alice/describe report :green/exit 0)))

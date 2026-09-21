(ns io.github.getcolors.alice.compute
  "Alice supplies one stable compute identity; the SDK owns application ordering."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [io.github.getcolors.alice.utils :as utils]
            [io.github.getcolors.compute-node :as compute]))

(def node-id "0")
(def state-filename "alice-node-0.tfstate")

(defn sdk-workdir [opts]
  (-> (utils/tool-dir opts "alice-infrastructure") io/file .getAbsoluteFile .getParentFile .getParentFile .getCanonicalPath))

(defn library-options [opts]
  ;; These paths are runtime outputs used by application stages, never key inputs.
  (dissoc opts :ssh-private-key-path :ssh-public-key-path :colors-compute/node))

(defn request [opts]
  (let [sources (or (:alice-ssh-sources opts) (:digitalocean-ssh-sources opts) ["0.0.0.0/0" "::/0"])
        sources (if (string? sources) (vec (remove str/blank? (str/split sources #"[,\s]+"))) sources)]
    {:node_id node-id :state_filename state-filename :workdir (sdk-workdir opts)
     :network (cond-> {:mode "discovered"} (contains? opts :digitalocean-vpc-uuid) (assoc :id (:digitalocean-vpc-uuid opts)))
     :security {:egress "all" :private_filter false
                :ingress [{:id "ssh" :protocol "tcp" :from_port 22 :to_port 22 :sources sources}
                          {:id "peers-tcp" :protocol "tcp" :from_port 51413 :to_port 51413 :sources ["0.0.0.0/0" "::/0"]}
                          {:id "peers-udp" :protocol "udp" :from_port 51413 :to_port 51413 :sources ["0.0.0.0/0" "::/0"]}]}}))

(defn plan [opts] (compute/node-plan (library-options opts) (request opts)))
(defn placeholder-key [opts] (str "/home/build-placeholder/compute/" (:profile opts) "/" node-id "/ssh-key"))
(defn placeholder-node [opts]
  (plan opts)
  {:node_id node-id :provider (:provider-compute opts) :name (str (:profile opts) "-" node-id)
   :ip "192.0.2.10" :user "root" :sudoer "root" :ssh_identity_file (placeholder-key opts)})

(defn node [opts]
  (or (:colors-compute/node opts)
      (when (or (= :build (:green/event opts)) (:green/dry-run opts)) (placeholder-node opts))
      (throw (ex-info "compute result unavailable; refusing placeholder inventory" {}))))

(ns io.github.getcolors.alice.compute
  "Alice supplies one stable compute identity; the SDK owns application ordering."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [io.github.getcolors.alice.utils :as utils]
            [io.github.getcolors.compute-node :as compute]
            [io.github.getcolors.compute-ssh :as ssh]))

(def node-id "0")
(def state-filename "alice-node-0.tfstate")

(defn sdk-workdir [opts]
  (-> (utils/tool-dir opts "alice-infrastructure") io/file .getAbsoluteFile .getParentFile .getParentFile .getCanonicalPath))

(defn library-options [opts]
  ;; These paths are runtime outputs used by application stages, never key inputs.
  (dissoc opts :ssh-private-key-path :ssh-public-key-path :colors-compute/node))

(def placeholder-resource
  {:status "ready" :reference "ssh-resource:build-placeholder" :public_key "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" :fingerprint "SHA256:kmYcvdi2GkPeWxB6XLjrZB8JHsy2Hm8luHMFp9GMvqk"})
(defn resource [opts] (or (:alice/ssh-resource opts) placeholder-resource))
(defn registration-request [opts]
  {:name "app-access" :workdir (sdk-workdir opts) :state_filename "alice-ssh-registration.tfstate"
   :ssh_resource (resource opts)})
(defn request [opts]
  (let [sources (or (:alice-ssh-sources opts) (:digitalocean-ssh-sources opts) ["0.0.0.0/0" "::/0"])
        sources (if (string? sources) (vec (remove str/blank? (str/split sources #"[,\s]+"))) sources)]
    {:node_id node-id :state_filename state-filename :workdir (sdk-workdir opts)
     :ssh_resource (resource opts)
     :ssh_registration (or (:alice/ssh-registration opts)
                           {:status "ready" :reference "registration:build-placeholder" :provider "digitalocean"
                            :ssh_resource_reference (:reference (resource opts))
                            :fingerprint (:fingerprint (resource opts)) :id "0"})
     :network (cond-> {:mode "discovered"} (contains? opts :digitalocean-vpc-uuid) (assoc :id (:digitalocean-vpc-uuid opts)))
     :security {:egress "all" :private_filter false
                :ingress [{:id "ssh" :protocol "tcp" :from_port 22 :to_port 22 :sources sources}
                          {:id "peers-tcp" :protocol "tcp" :from_port 51413 :to_port 51413 :sources ["0.0.0.0/0" "::/0"]}
                          {:id "peers-udp" :protocol "udp" :from_port 51413 :to_port 51413 :sources ["0.0.0.0/0" "::/0"]}]}}))

(defn ssh-request [opts]
  {:name "app-access" :workdir (sdk-workdir opts) :passphrase_env "COLORS_PAR_ALICE_SSH_PASSPHRASE"})
(defn plan [opts]
  (ssh/ssh-plan (library-options opts) (ssh-request opts))
  (compute/node-plan (library-options opts) (request opts)))
(defn placeholder-key [opts] (str "/home/build-placeholder/compute/" (:profile opts) "/" node-id "/identity.pub"))
(defn placeholder-node [opts]
  (plan opts)
  {:node_id node-id :provider (:provider-compute opts) :name (str (:profile opts) "-" node-id)
   :ip "192.0.2.10" :user "root" :sudoer "root" :ssh_identity_file (placeholder-key opts)})

(defn node [opts]
  (or (:colors-compute/node opts)
      (when (or (= :build (:green/event opts)) (:green/dry-run opts)) (placeholder-node opts))
      (throw (ex-info "compute result unavailable; refusing placeholder inventory" {}))))

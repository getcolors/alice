(ns io.github.getcolors.alice.tools
  "DigitalOcean, local SSH, Transmission, and tunnel acceptance stages."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [green.ansible :as ansible]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.workflow :as wf]
            [io.github.getcolors.alice.ssh-config :as ssh-config]
            [io.github.getcolors.alice.compute :as compute]
            [io.github.getcolors.alice.compute-error :as compute-error]
            [io.github.getcolors.compute-node :as library]
            [io.github.getcolors.alice.utils :as utils]
            [io.github.getcolors.alice.validate :as validate]))

(def infrastructure-tool "alice-infrastructure")
(def ansible-local-tool "alice-ansible-local")
(def ansible-remote-tool "alice-ansible-remote")
(def acceptance-tool "alice-acceptance")

(def ^:private root "io.github.getcolors.alice.tools")
(def ^:private template-opts sc/preserve-jinja-delimiters)

(defn template [path file] (keyword (str root "." path) file))
(defn spec [template target data]
  {:template template :target target :data data :opts template-opts})
(defn raw-spec [target content] (sc/content-spec target content))
(defn tool-dir [opts tool] (utils/tool-dir opts tool))

(defn- compute-json [value indent]
  (let [padding #(apply str (repeat % " "))]
    (cond
      (map? value) (if (empty? value) "{}"
                      (str "{\n" (str/join ",\n" (for [[key item] (sort-by (comp name key) value)]
                                                       (str (padding (+ indent 2)) (json/generate-string key) ": " (compute-json item (+ indent 2)))))
                           "\n" (padding indent) "}"))
      (sequential? value) (if (empty? value) "[]"
                              (str "[\n" (str/join ",\n" (map #(str (padding (+ indent 2)) (compute-json % (+ indent 2))) value)) "\n" (padding indent) "]"))
      :else (json/generate-string value))))

(defn- with-node [opts node]
  (cond-> (assoc opts :colors-compute/node node :ip (:ip node) :user (:user node) :green/exit 0)
    (:ssh_identity_file node) (assoc :ssh-private-key-path (:ssh_identity_file node))))

(defn infrastructure-step [opts]
  (try
    (let [planning? (or (= :build (:green/event opts)) (:green/dry-run opts))
          deleting? (= :delete (:green/event opts))
          options (assoc (compute/library-options opts) :compute-prevent-destroy (not deleting?))
          result (if planning?
                   (library/build-node! options (compute/request opts))
                   (library/compute-node! options (compute/request opts) (if deleting? "delete" "create")))]
      (case (:status result)
        "built" (with-node opts (compute/placeholder-node opts))
        "ready" (with-node opts (:params result))
        "destroyed" (assoc opts :green/exit 0)
        (compute-error/failed-result opts result)))
    (catch InterruptedException e (throw e))
    (catch Exception _ (compute-error/failed-result opts {:status "error"}))))

(defn load-infrastructure-step [opts]
  (try
    (let [result (library/compute-node! (compute/library-options opts) (compute/request opts)
                                       (if (= :delete (:green/event opts)) "inspect" "prepare-access"))]
      (case (:status result)
        "ready" (with-node opts (:params result))
        "destroyed" (if (= :delete (:green/event opts))
                      (assoc opts :alice/already-destroyed true :green/exit 0)
                      (assoc opts :green/exit 1 :green/err "compute node is destroyed"))
        (compute-error/failed-result opts result)))
    (catch InterruptedException e (throw e))
    (catch Exception _ (compute-error/failed-result opts {:status "error"}))))

(defn data-fn [opts]
  (let [node (compute/node opts)]
    (cond-> (merge opts {:host-alias (utils/host-alias opts) :ip (:ip node) :user (:user node)})
      (:ssh_identity_file node) (assoc :ssh-private-key-path (:ssh_identity_file node)))))

(defn inventory [opts]
  (let [{:keys [host-alias ip user ssh-private-key-path]} (data-fn opts)]
    (json/generate-string
      {:all {:hosts {host-alias (cond-> {:ansible_host ip :ansible_user user}
                                 ssh-private-key-path (assoc :ansible_ssh_private_key_file ssh-private-key-path)
                                 (validate/keygen? opts) (assoc :ansible_ssh_common_args "-o IdentitiesOnly=yes -o IdentityAgent=none"))}}}
      {:pretty true})))

(defn ansible-local-specs [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        data (assoc (data-fn opts)
                    :ssh-keygen (validate/keygen? opts))]
    [(spec (template "ansible-local" "ansible.cfg")
           (str dir "/ansible.cfg") data)
     (spec (template "ansible-local" "inventory.ini")
           (str dir "/inventory.ini") data)
     (spec (template "ansible-local" "main.yml")
           (str dir "/main.yml") data)]))

(defn ansible-local-step [opts]
  (let [dir (tool-dir opts ansible-local-tool)
        data (data-fn opts)
        delete? (= :delete (:green/event opts))]
    (ansible/ansible-with-spec
     opts
     {:dir dir :inventory "inventory.ini"
      :playbooks {:create "main.yml" :delete "main.yml"}
      :extra-vars {:host_alias (:host-alias data)
                   :ssh_identity_file (:ssh-private-key-path data)
                   :ssh_hosts [{:name (:host-alias data) :ip (:ip data) :user (:user data)}]
                   :ssh_legacy_marker_prefix "alice"
                   :block_state (if delete? "absent" "present")}}
     (ansible-local-specs opts))))

(defn ansible-remote-specs [opts]
  (let [dir (tool-dir opts ansible-remote-tool)
        data (data-fn opts)]
    [(spec (template "ansible-remote" "ansible.cfg")
           (str dir "/ansible.cfg") data)
     (spec (template "ansible-remote" "main.yml")
           (str dir "/main.yml") data)
     (raw-spec (str dir "/inventory.json") (inventory data))]))

(defn ansible-remote-step [opts]
  (let [dir (tool-dir opts ansible-remote-tool)
        rendered (sc/scaffold opts (ansible-remote-specs opts))]
    (if (= :build (:green/event opts))
      rendered
      (ansible/ansible-step
       rendered {:dir dir :inventory "inventory.json"
                 :playbooks {:create "main.yml"}
                 :host-key-checking false}))))

(defn acceptance-specs [opts]
  (let [dir (tool-dir opts acceptance-tool)]
    [(spec (template "acceptance" "acceptance.sh")
           (str dir "/acceptance.sh") (data-fn opts))]))

(defn process-result [opts label {:keys [exit out err]}]
  (if (zero? exit)
    (assoc opts :green/exit 0)
    (assoc opts :green/exit (max 1 exit)
                :green/err (str label " failed: "
                                (or (not-empty err) (not-empty out)
                                    "(no output)")))))

(defn acceptance-step [opts]
  (let [rendered (sc/scaffold opts (acceptance-specs opts))]
    (if (not= :create (:green/event opts))
      rendered
      (process-result
       rendered "acceptance"
       (process/run-with-timeout
        ["bash" (str (tool-dir opts acceptance-tool) "/acceptance.sh")]
        {} 180000)))))

(defn generated-cleanup-step [opts]
  ;; Fixed generated targets need no inventory or removed SSH identity.
  (if (= :delete (:green/event opts))
    (sc/scaffold opts
      (vec (for [[tool names] [[ansible-remote-tool ["ansible.cfg" "main.yml" "inventory.json"]] [acceptance-tool ["acceptance.sh"]]] name names]
        (raw-spec (str (tool-dir opts tool) "/" name) ""))))
    opts))

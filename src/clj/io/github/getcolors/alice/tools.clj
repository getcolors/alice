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
            [io.github.getcolors.compute :as library]
            [io.github.getcolors.compute-planning :as planning]
            [io.github.getcolors.compute-orchestration :as orchestration]
            [io.github.getcolors.compute-inspection :as inspection]
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

(defn infrastructure-step [opts]
  (try
    (let [planning? (or (= :build (:green/event opts)) (:green/dry-run opts))
          result (if planning?
                   (planning/plan-deployment opts (compute/topology opts) (compute/requirements opts))
                   (orchestration/orchestrate (assoc opts :green/event (if (= :sync (:green/event opts)) :create (:green/event opts)) :compute-prevent-destroy (not= :delete (:green/event opts))) (compute/topology opts) (compute/requirements opts)))]
      (when planning?
        (doseq [[stage key] (cons ["shared" (get-in result [:state_keys :shared])]
                                 (map (fn [[id key]] [(str "nodes/" (name id)) key]) (get-in result [:state_keys :nodes]))) ]
          (let [target (io/file (tool-dir opts infrastructure-tool) stage "backend.tf.json")]
            (io/make-parents target)
            (spit target (str (compute-json (:config (library/backend-plan opts key)) 0) "\n"))))
        (doseq [[stage documents] (cons ["shared" (get-in result [:documents :shared])]
                                      (map (fn [[id documents]] [(str "nodes/" id) documents]) (get-in result [:documents :nodes])))
                [filename document] documents]
          (let [target (io/file (tool-dir opts infrastructure-tool) stage filename)]
            (io/make-parents target)
            (spit target (str (compute-json document 0) "\n")))))
      (if-not (contains? #{"ready" "planned" "destroyed"} (:status result))
        (assoc opts :green/exit 1 :green/err (if (seq (:errors result)) (str/join "\n" (:errors result)) "compute lifecycle refused; inspect state ownership and configuration"))
        (cond-> (assoc opts :green/exit 0)
          (:shared result) (assoc :colors-compute/shared (:shared result))
          (:cluster result) (assoc :colors-compute/cluster (:cluster result) :ip (get-in result [:cluster :nodes 0 :ip]) :user (get-in result [:cluster :nodes 0 :user]))
          (get-in result [:key :private_key_path])
          (assoc :ssh-private-key-path (if planning? (str/replace (get-in result [:key :private_key_path]) "$HOME/.ssh" "/home/build-placeholder/.ssh") (get-in result [:key :private_key_path]))))))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "compute lifecycle refused; legacy monolithic state requires explicit migration"))))

(defn load-infrastructure-step [opts]
  (try
    (let [result (inspection/read-deployment opts (into {} (System/getenv)) {} (compute/requirements opts))]
      (case (:status result)
        "present" (let [node (first (get-in result [:cluster :nodes]))]
                    (cond-> (assoc opts :colors-compute/cluster (:cluster result)
                                       :colors-compute/shared (:shared result)
                                       :ip (:ip node) :user (:user node) :green/exit 0)
                      (:ssh_identity_file node) (assoc :ssh-private-key-path (:ssh_identity_file node))))
        "destroyed" (if (= :delete (:green/event opts)) (assoc opts :alice/already-destroyed true :green/exit 0)
                        (assoc opts :green/exit 1 :green/err "compute deployment is destroyed"))
        (assoc opts :green/exit 1 :green/err "compute inspection refused; existing owned state is required")))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "compute inspection refused; existing owned state is required"))))

(defn data-fn [opts]
  (let [node (compute/node opts)]
    (merge opts {:host-alias (utils/host-alias opts) :ip (:ip node) :user (:user node)})))

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
                    :ssh-keygen (validate/keygen? opts)
                    :ssh-config-identity-file (ssh-config/identity-file opts))]
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
  (-> opts
      (sc/scaffold (ansible-remote-specs opts))
      (sc/scaffold (acceptance-specs opts))))

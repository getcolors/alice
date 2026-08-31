(ns io.github.getcolors.alice.tools
  "DigitalOcean, local SSH, Transmission, and tunnel acceptance stages."
  (:require [cheshire.core :as json]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.process :as process]
            [green.providers :as provider-ops]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.alice.ssh-config :as ssh-config]
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

(defn credential-env [opts & slots]
  (provider-ops/tool-env validate/providers opts
                         (conj (vec slots) :provider-backend)))

(defn infrastructure-specs [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        ;; The desired-state guard is never environment-controlled. An explicit
        ;; delete event is the sole capability that renders a destroyable plan.
        data (assoc opts
                    :compute-prevent-destroy (not= :delete (:green/event opts))
                    :ssh-keygen (validate/keygen? opts)
                    :vpc-discovery (validate/vpc-discovery? opts)
                    :compute-name (validate/compute-name opts))]
    [(spec (template "infrastructure" "main.tf")
           (str dir "/main.tf") data)]))

(def fallback-params
  {:ip "192.0.2.10" :user "root" :sudoer "root" :name "alice"})

(defn state-output
  "The compute stage's applied `params`, or nil when no state is readable. The
  SSH Keypair Standard's create matrix keys on this best-effort read: an
  unreadable state (a fresh clone, a missing backend) counts as absent."
  [opts]
  (try (some-> (tofu/outputs (tool-dir opts infrastructure-tool)
                             (credential-env opts))
               :params walk/keywordize-keys)
       (catch Exception _ nil)))

(defn- output-params [result]
  (some-> (get-in result [:tofu/outputs :params]) walk/keywordize-keys))

(defn infrastructure-step [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        result (tofu/tofu-with-spec
                opts (infrastructure-specs opts)
                {:dir dir :env (credential-env opts :provider-compute)})]
    (cond
      (wf/failed? result) result
      (= :build (:green/event opts))
      (merge result fallback-params {:name (utils/host-alias opts)})
      (= :delete (:green/event opts)) result
      :else (merge result fallback-params
                   {:name (utils/host-alias opts)}
                   (output-params result)))))

(defn data-fn [opts]
  (merge fallback-params opts
         {:host-alias (utils/host-alias opts)
          :ip (or (:ip opts) (:ip fallback-params))
          :user (or (:user opts) "root")}))

(defn inventory
  "The remote inventory: one root host, and in keygen mode the path to the
  machine key.

  `ansible.cfg` runs the connection with `-F /dev/null` on purpose — the run
  must not depend on `~/.ssh/config`, a file shared with every other host the
  operator reaches and rewritten by the local stage while the run is in
  flight. That isolation also discards the `IdentityFile` the managed block
  names, so in keygen mode nothing offers the generated key unless an agent
  happens to hold it, and a create that worked yesterday fails today with
  `Permission denied (publickey)`. Naming the path here is what ONCE does for
  the same reason (`once.tools/inventory`): a path, never key material.

  The key alone is not enough: without `IdentitiesOnly` the agent's keys are
  offered ahead of it, and stale copies of superseded machine keys — added by
  a workstation's `AddKeysToAgent` and outliving the deleted file — exhaust
  the server's `MaxAuthTries` as `Too many authentication failures` before
  the named key is reached. The generated key is passphrase-less and
  ephemeral, so the agent contributes nothing here; `IdentityAgent none`
  both ignores it and keeps this connection from feeding it another copy.
  Opt-out mode stays silent: the operator supplied the key, and how their
  ssh finds it — agent included — is their arrangement to keep."
  [opts]
  (let [{:keys [host-alias ip user ssh-private-key-path]} (data-fn opts)]
    (json/generate-string
     {:all {:hosts {host-alias (cond-> {:ansible_host ip :ansible_user user}
                                 (validate/keygen? opts)
                                 (assoc :ansible_ssh_private_key_file
                                        ssh-private-key-path
                                        :ansible_ssh_common_args
                                        "-o IdentitiesOnly=yes -o IdentityAgent=none"))}}}
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
                   :ip (:ip data)
                   :user (:user data)
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

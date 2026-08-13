(ns io.github.getcolors.alice.workflow
  "Alice lifecycle DAG, validation, and package-specific backend state key."
  (:require [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.alice.describe :as describe]
            [io.github.getcolors.alice.tools :as tools]
            [io.github.getcolors.alice.validate :as validate]))

(def defaults
  {:compute-prevent-destroy true
   :provider-compute "digitalocean"
   :provider-dns false
   :provider-backend "local"
   :package "alice"
   :workdir ".colors"
   :ssh-identity-file "~/.ssh/id_ed25519"
   :transmission-rpc-port 9091
   :transmission-tunnel-local-port 19091})

(def credential-events #{:create :delete :validate})

(defn start-step
  ([opts] (start-step opts (System/getenv) validate/runtime-errors))
  ([opts env] (start-step opts env validate/runtime-errors))
  ([opts env runtime-errors-fn]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (credential-events event))
               (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (credential-events event))
               (runtime-errors-fn opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event)
                        (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy)
                     "=false for this delete")]))]}
    env)))

(defn wire-fn [step run-opts]
  (case (:green/event run-opts)
    :delete
    (case step
      :alice/start [start-step :alice/ansible-local]
      :alice/ansible-local [tools/ansible-local-step :alice/infrastructure]
      :alice/infrastructure [tools/infrastructure-step :alice/generated-cleanup]
      :alice/generated-cleanup [tools/generated-cleanup-step])

    :validate
    (case step
      :alice/start [start-step])

    :describe
    (case step
      :alice/start [start-step :alice/describe]
      :alice/describe [describe/describe-step])

    (case step
      :alice/start [start-step :alice/infrastructure]
      :alice/infrastructure [tools/infrastructure-step :alice/ansible-local]
      :alice/ansible-local [tools/ansible-local-step :alice/ansible-remote]
      :alice/ansible-remote [tools/ansible-remote-step :alice/acceptance]
      :alice/acceptance [tools/acceptance-step])))

(defn backend-advice []
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tools/infrastructure-tool)
    :key-fn #(str (:profile %) "/" tools/infrastructure-tool ".tfstate")}))

(def side-effecting-steps
  [:alice/infrastructure :alice/ansible-local :alice/ansible-remote
   :alice/acceptance :alice/generated-cleanup])

(def workflow
  (-> (wf/workflow {:start :alice/start :wire-fn wire-fn})
      (wf/advice-add :alice/infrastructure :before ::backend
                     (backend-advice))
      progress/advise
      (dry-run/advise side-effecting-steps)))

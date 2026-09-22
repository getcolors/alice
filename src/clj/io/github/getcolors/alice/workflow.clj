(ns io.github.getcolors.alice.workflow
  "Alice lifecycle DAG, validation, and package-specific backend state key."
  (:require [green.dry-run :as dry-run]
            [clojure.java.io :as io]
            [io.github.getcolors.alice.access :as access]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.workflow :as wf]
            [io.github.getcolors.alice.describe :as describe]
            [io.github.getcolors.alice.ssh :as ssh]
            [io.github.getcolors.alice.ssh-config :as ssh-config]
            [io.github.getcolors.alice.sync :as sync]
            [io.github.getcolors.alice.tools :as tools]
            [io.github.getcolors.alice.validate :as validate]
            [io.github.getcolors.alice.compute :as compute]))

(def defaults
  {:compute-prevent-destroy true
   :provider-compute "digitalocean"
   :provider-dns false
   :provider-backend "r2"
   :workdir ".colors"
   :transmission-rpc-port 9091
   :transmission-tunnel-local-port 19091})

(def credential-events #{:validate})

(def create-like-events #{:create :sync})

(defn start-step
  ([opts] (start-step opts (System/getenv) validate/runtime-errors))
  ([opts env] (start-step opts env validate/runtime-errors))
  ([opts env runtime-errors-fn]
   ;; green.cli has already applied generic COLORS_PAR_* overlays before the
   ;; package preflight runs. Restore this package-owned guard so the retired
   ;; COMPUTE_PREVENT_DESTROY overlay is genuinely inert.
   (lifecycle/preflight
    (assoc opts :compute-prevent-destroy true)
    {:defaults defaults :overlay validate/overlay
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event]}]
             (when (= :validate event)
               (try (compute/plan opts) []
                    (catch Exception e [(ex-message e)]))))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (credential-events event))
               (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (#{:create :delete :sync :validate} event))
               (runtime-errors-fn opts)))]
          :after-validate
          (fn [opts _ {:keys [event real?]}]
            (let [opts (cond-> opts (= :build event) (update :workdir #(str (io/file % "build"))))]
              (if (and real? (create-like-events event)) (ssh-config/preflight! opts)
                  (assoc (if real? opts (ssh/with-machine-key opts)) :green/exit 0))))}
    env)))

(defn as-event
  "Run a sync teardown step under :delete, restoring the outer event afterward.
  Application cleanup and compute guards require the explicit delete event."
  [event step]
  (fn [opts]
    (let [outer-event (:green/event opts)
          result (step (assoc opts :green/event event))]
      (assoc result :green/event outer-event))))

(def sync-local-delete-step (as-event :delete tools/ansible-local-step))
(def sync-infrastructure-delete-step (as-event :delete tools/infrastructure-step))
(def sync-generated-cleanup-step (as-event :delete tools/generated-cleanup-step))

(defn wire-fn [step run-opts]
  (case (:green/event run-opts)
    :delete
    (case step
      ;; Remove the owned alias before compute; retain the SSH authority.
      :alice/start [start-step :alice/ssh-resource]
      :alice/ssh-resource [access/resource-step :alice/load-infrastructure]
      :alice/load-infrastructure [tools/load-infrastructure-step :alice/ansible-local]
      :alice/ansible-local [tools/ansible-local-step :alice/infrastructure]
      :alice/infrastructure [tools/infrastructure-step :alice/registration-delete]
      :alice/registration-delete [access/registration-delete-step :alice/generated-cleanup]
      :alice/generated-cleanup [tools/generated-cleanup-step])

    ;; Sync authorizes teardown only after its final checksummed copy.
    :sync
    (case step
      :alice/start [start-step :alice/ssh-resource]
      :alice/ssh-resource [access/resource-step :alice/registration :alice/agent]
      :alice/registration [access/registration-step :alice/infrastructure]
      :alice/agent [access/agent-step :alice/access-ready]
      :alice/access-ready [access/join-step :alice/ansible-local]
      :alice/infrastructure [tools/infrastructure-step :alice/access-ready]
      :alice/ansible-local [tools/ansible-local-step :alice/ansible-remote]
      :alice/ansible-remote [tools/ansible-remote-step :alice/sync]
      :alice/sync [sync/sync-step :alice/sync-ansible-local-delete]
      :alice/sync-ansible-local-delete [sync-local-delete-step :alice/sync-infrastructure-delete]
      :alice/sync-infrastructure-delete [sync-infrastructure-delete-step :alice/registration-delete]
      :alice/registration-delete [access/registration-delete-step :alice/sync-generated-cleanup]
      :alice/sync-generated-cleanup [sync-generated-cleanup-step])

    :validate
    (case step
      :alice/start [start-step])

    :describe
    (case step
      :alice/start [start-step :alice/ssh-resource]
      :alice/ssh-resource [access/resource-step :alice/agent]
      :alice/agent [access/agent-step :alice/load-infrastructure]
      :alice/load-infrastructure [tools/load-infrastructure-step :alice/describe]
      :alice/describe [describe/describe-step])

    (case step
      :alice/start [start-step :alice/ssh-resource]
      :alice/ssh-resource [access/resource-step :alice/registration :alice/agent]
      :alice/registration [access/registration-step :alice/infrastructure]
      :alice/agent [access/agent-step :alice/access-ready]
      :alice/access-ready [access/join-step :alice/ansible-local]
      :alice/infrastructure [tools/infrastructure-step :alice/access-ready]
      :alice/ansible-local [tools/ansible-local-step :alice/ansible-remote]
      :alice/ansible-remote [tools/ansible-remote-step :alice/acceptance]
      :alice/acceptance [tools/acceptance-step])))

(def side-effecting-steps
  [:alice/ssh-resource :alice/registration :alice/agent :alice/registration-delete :alice/load-infrastructure :alice/infrastructure :alice/ansible-local :alice/ansible-remote
   :alice/acceptance :alice/sync :alice/sync-ansible-local-delete
   :alice/sync-infrastructure-delete
   :alice/sync-generated-cleanup
   :alice/generated-cleanup])

(defn next-steps [step successors opts]
  (cond
    (wf/failed? opts) []
    (and (:alice/already-destroyed opts) (= :delete (:green/event opts)) (= :alice/load-infrastructure step))
    [[:alice/registration-delete opts]]
    :else (mapv #(vector % opts) successors)))

(def workflow
  (-> (wf/workflow {:start :alice/start :wire-fn wire-fn
                    :next-fn next-steps})
      progress/advise
      (dry-run/advise side-effecting-steps)))

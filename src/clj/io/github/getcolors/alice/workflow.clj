(ns io.github.getcolors.alice.workflow
  "Alice lifecycle DAG, validation, and package-specific backend state key."
  (:require [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.workflow :as wf]
            [io.github.getcolors.alice.describe :as describe]
            [io.github.getcolors.alice.ssh :as ssh]
            [io.github.getcolors.alice.ssh-config :as ssh-config]
            [io.github.getcolors.alice.sync :as sync]
            [io.github.getcolors.alice.tools :as tools]
            [io.github.getcolors.alice.validate :as validate]
            [io.github.getcolors.alice.compute :as compute]
            [io.github.getcolors.compute-planning :as planning]))

(def defaults
  {:compute-prevent-destroy true
   :provider-compute "digitalocean"
   :provider-dns false
   :provider-backend "r2"
   :workdir ".colors"
   :transmission-rpc-port 9091
   :transmission-tunnel-local-port 19091})

(def credential-events #{:validate})

(def create-like-events
  "Events that bring a Droplet into existence, and therefore own the key's
  creation. `sync` is here because alice's `sync` *is* the lifecycle: it
  creates, downloads, and destroys in one event. The SSH Keypair Standard §3
  bars a `sync` from touching key material because in every other package sync
  is auxiliary and leaves the machine alone; alice's does not, and a key that
  never appears cannot give a Droplet access. The DAG relabels the phases so
  what actually runs is a create and a delete."
  #{:create :sync})

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
               (try (planning/validate-deployment opts (compute/topology opts) (compute/requirements opts)) []
                    (catch Exception e [(ex-message e)]))))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (credential-events event))
               (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (#{:create :delete :sync :validate} event))
               (runtime-errors-fn opts)))]
          :after-validate
          (fn [opts _ {:keys [event real?]}]
            (if (and real? (create-like-events event)) (ssh-config/preflight! opts)
                (assoc (if real? opts (ssh/with-machine-key opts)) :green/exit 0)))}
    env)))

(defn as-event
  "Run `step` under a different `:green/event`, restoring the caller's event
  on the way out.

  This is what lets `sync` host a real delete. The key and config-block steps
  gate on `:green/event`, per the standards, so a teardown that announced
  itself as `:sync` would be skipped — `cleanup-step` would leave the keypair
  behind on every ephemeral run."
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
      ;; The `~/.ssh/config` block goes before the destroy, the keypair after
      ;; it. A block that outlives its host is stale but harmless; a key that
      ;; predeceases its host locks the operator out of a machine that still
      ;; exists. Both orders are deliberate — standards/ssh-config.md §4 is
      ;; explicit that they must not be tidied into agreement.
      :alice/start [start-step :alice/load-infrastructure]
      :alice/load-infrastructure [tools/load-infrastructure-step :alice/ansible-local]
      :alice/ansible-local [tools/ansible-local-step :alice/infrastructure]
      :alice/infrastructure [tools/infrastructure-step :alice/generated-cleanup]
      :alice/generated-cleanup [tools/generated-cleanup-step])

    ;; Alice's `sync` is the whole lifecycle in one event, so it carries both
    ;; orderings above: the key is generated before the first provider call in
    ;; `start`, and removed only once the destroy below has succeeded. The
    ;; teardown steps run relabelled as `:delete` — `sync` has no key
    ;; lifecycle of its own, it hosts a create and a delete.
    :sync
    (case step
      :alice/start [start-step :alice/infrastructure]
      :alice/infrastructure [tools/infrastructure-step :alice/ansible-local]
      :alice/ansible-local [tools/ansible-local-step :alice/ansible-remote]
      :alice/ansible-remote [tools/ansible-remote-step :alice/sync]
      :alice/sync [sync/sync-step :alice/sync-ansible-local-delete]
      :alice/sync-ansible-local-delete [sync-local-delete-step :alice/sync-infrastructure-delete]
      :alice/sync-infrastructure-delete [sync-infrastructure-delete-step :alice/sync-generated-cleanup]
      :alice/sync-generated-cleanup [sync-generated-cleanup-step])

    :validate
    (case step
      :alice/start [start-step])

    :describe
    (case step
      :alice/start [start-step :alice/load-infrastructure]
      :alice/load-infrastructure [tools/load-infrastructure-step :alice/describe]
      :alice/describe [describe/describe-step])

    (case step
      :alice/start [start-step :alice/infrastructure]
      :alice/infrastructure [tools/infrastructure-step :alice/ansible-local]
      :alice/ansible-local [tools/ansible-local-step :alice/ansible-remote]
      :alice/ansible-remote [tools/ansible-remote-step :alice/acceptance]
      :alice/acceptance [tools/acceptance-step])))

(def side-effecting-steps
  [:alice/load-infrastructure :alice/infrastructure :alice/ansible-local :alice/ansible-remote
   :alice/acceptance :alice/sync :alice/sync-ansible-local-delete
   :alice/sync-infrastructure-delete
   :alice/sync-generated-cleanup
   :alice/generated-cleanup])

(def workflow
  (-> (wf/workflow {:start :alice/start :wire-fn wire-fn
                    :next-fn (fn [_ successors opts] (if (or (wf/failed? opts) (:alice/already-destroyed opts)) [] (mapv #(vector % opts) successors)))})
      progress/advise
      (dry-run/advise side-effecting-steps)))

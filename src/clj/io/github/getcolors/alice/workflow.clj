(ns io.github.getcolors.alice.workflow
  "Alice lifecycle DAG, validation, and package-specific backend state key."
  (:require [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.alice.describe :as describe]
            [io.github.getcolors.alice.ssh :as ssh]
            [io.github.getcolors.alice.ssh-config :as ssh-config]
            [io.github.getcolors.alice.sync :as sync]
            [io.github.getcolors.alice.tools :as tools]
            [io.github.getcolors.alice.validate :as validate]))

(def defaults
  {:compute-prevent-destroy true
   :provider-compute "digitalocean"
   :provider-dns false
   :provider-backend "local"
   :workdir ".colors"
   :transmission-rpc-port 9091
   :transmission-tunnel-local-port 19091})

(def credential-events #{:create :delete :sync :validate})

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
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (credential-events event))
               (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (credential-events event))
               (runtime-errors-fn opts)))]
          :after-validate
          ;; The machine key's create matrix, the DigitalOcean preflight, and
          ;; the `~/.ssh/config` ownership checks all run before any template
          ;; is rendered: an unowned key on disk, at the provider, or a `Host`
          ;; stanza someone else wrote stops the run while stopping is free.
          ;; Delete fills the same template values — a destroy renders before
          ;; it destroys — but checks nothing, because its key cleanup runs
          ;; after the compute destroy.
          (fn [opts _ {:keys [event real?]}]
            (cond
              (and real? (= :delete event))
              (merge (ssh/with-machine-key opts)
                     (or (tools/state-output opts) {})
                     {:green/exit 0})

              (and real? (create-like-events event))
              (let [opts (ssh/ensure-key! opts tools/state-output)]
                (if (wf/failed? opts)
                  opts
                  (let [opts (ssh/preflight! (ssh/with-machine-key opts))
                        opts (if (wf/failed? opts) opts (ssh-config/preflight! opts))]
                    (if (wf/failed? opts) opts (assoc opts :green/exit 0)))))

              :else
              (assoc (ssh/with-machine-key opts) :green/exit 0)))}
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
(def sync-ssh-cleanup-step (as-event :delete ssh/cleanup-step))
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
      :alice/start [start-step :alice/ansible-local]
      :alice/ansible-local [tools/ansible-local-step :alice/infrastructure]
      :alice/infrastructure [tools/infrastructure-step :alice/ssh-cleanup]
      :alice/ssh-cleanup [ssh/cleanup-step :alice/generated-cleanup]
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
      :alice/sync-infrastructure-delete [sync-infrastructure-delete-step :alice/sync-ssh-cleanup]
      :alice/sync-ssh-cleanup [sync-ssh-cleanup-step :alice/sync-generated-cleanup]
      :alice/sync-generated-cleanup [sync-generated-cleanup-step])

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
   :alice/acceptance :alice/sync :alice/sync-ansible-local-delete
   :alice/sync-infrastructure-delete :alice/sync-ssh-cleanup
   :alice/sync-generated-cleanup :alice/ssh-cleanup
   :alice/generated-cleanup])

(def workflow
  (-> (wf/workflow {:start :alice/start :wire-fn wire-fn})
      (wf/advice-add :alice/infrastructure :before ::backend
                     (backend-advice))
      progress/advise
      (dry-run/advise side-effecting-steps)))

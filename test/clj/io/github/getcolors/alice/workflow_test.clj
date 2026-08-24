(ns io.github.getcolors.alice.workflow-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.workflow :as wf]
            [io.github.getcolors.alice.tools :as tools]
            [io.github.getcolors.alice.validate-test :as vt]
            [io.github.getcolors.alice.workflow :as workflow]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "alice-test-" "")]
    (.delete f) (.mkdirs f) (str f)))

(defn- next-steps [event step]
  (rest (workflow/wire-fn step {:green/event event})))

(deftest create-orders-infrastructure-ssh-transmission-and-acceptance
  (is (= [:alice/infrastructure] (next-steps :create :alice/start)))
  (is (= [:alice/ansible-local] (next-steps :create :alice/infrastructure)))
  (is (= [:alice/ansible-remote] (next-steps :create :alice/ansible-local)))
  (is (= [:alice/acceptance] (next-steps :create :alice/ansible-remote))))

(deftest delete-removes-local-alias-before-droplet-and-key-after-it
  (is (= [:alice/ansible-local] (next-steps :delete :alice/start)))
  (is (= [:alice/infrastructure] (next-steps :delete :alice/ansible-local)))
  ;; The two orderings the standards deliberately disagree on: the config
  ;; block goes before the destroy (stale but harmless if the destroy fails),
  ;; the keypair strictly after it (a key that predeceases its Droplet locks
  ;; the operator out of a machine that still exists).
  (is (= [:alice/ssh-cleanup] (next-steps :delete :alice/infrastructure)))
  (is (= [:alice/generated-cleanup] (next-steps :delete :alice/ssh-cleanup))))

(deftest sync-owns-download-copy-and-successful-cleanup
  (is (= [:alice/infrastructure] (next-steps :sync :alice/start)))
  (is (= [:alice/ansible-local] (next-steps :sync :alice/infrastructure)))
  (is (= [:alice/ansible-remote] (next-steps :sync :alice/ansible-local)))
  (is (= [:alice/sync] (next-steps :sync :alice/ansible-remote)))
  (is (= [:alice/sync-ansible-local-delete]
         (next-steps :sync :alice/sync)))
  (is (= [:alice/sync-infrastructure-delete]
         (next-steps :sync :alice/sync-ansible-local-delete)))
  ;; Sync carries the same ordering as delete, because sync *is* a create and
  ;; a delete: the key can only go once the destroy has succeeded.
  (is (= [:alice/sync-ssh-cleanup]
         (next-steps :sync :alice/sync-infrastructure-delete)))
  (is (= [:alice/sync-generated-cleanup]
         (next-steps :sync :alice/sync-ssh-cleanup))))

(deftest sync-teardown-steps-run-relabelled-as-delete
  ;; The key and block steps gate on `:green/event`, so the relabelling is what
  ;; lets one implementation serve both verbs. Without it `cleanup-step` would
  ;; see `:sync`, skip, and leave the keypair behind on every ephemeral run.
  (let [seen (atom nil)
        spy (fn [opts] (reset! seen (:green/event opts)) (assoc opts :green/exit 0))
        result ((workflow/as-event :delete spy) {:green/event :sync})]
    (is (= :delete @seen) "the wrapped step runs as a delete")
    (is (= :sync (:green/event result)) "and the caller's event is restored")))

(deftest sync-cleans-the-keypair-up-only-in-keygen-mode
  ;; Opt-out mode must not delete key material it did not create — the
  ;; operator supplied that key and may use it for other things.
  (let [optout (assoc vt/base :green/event :sync :digitalocean-ssh-keys "812184")]
    (is (= 0 (:green/exit (workflow/sync-ssh-cleanup-step optout))))))

(deftest validate-and-describe-have-dedicated-graphs
  (is (empty? (next-steps :validate :alice/start)))
  (is (= [:alice/describe] (next-steps :describe :alice/start))))

(deftest build-and-dry-run-need-no-credentials-or-runtime-checks
  (let [must-not-run #(throw (ex-info "runtime validation ran" {}))]
    (is (= 0 (:green/exit
              (workflow/start-step (assoc vt/base :green/event :build)
                                   {} must-not-run))))
    (is (= 0 (:green/exit
              (workflow/start-step
               (assoc vt/base :green/event :create :green/dry-run true)
               {} must-not-run))))))

(deftest generic-destruction-overlay-is-inert-before-package-preflight
  (let [result (workflow/start-step
                (assoc vt/base :green/event :build
                               :compute-prevent-destroy false)
                {"COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false"}
                #(throw (ex-info "runtime validation ran" {})))]
    (is (= 0 (:green/exit result)))
    (is (true? (:compute-prevent-destroy result)))))

(deftest real-lifecycle-needs-token-and-events-authorize-deletion
  (let [runtime-ok (constantly [])]
    (is (= 2 (:green/exit
              (workflow/start-step (assoc vt/base :green/event :create)
                                   {} runtime-ok))))
    (let [env {"COLORS_PAR_DO_TOKEN" "x"}]
      (doseq [event [:create :delete :sync]]
        (is (= 0 (:green/exit
                  (workflow/start-step (assoc vt/base :green/event event)
                                       env runtime-ok))))))))

(deftest backend-key-is-package-specific
  (let [dir (temp-dir)
        opts (merge vt/base {:profile "p" :workdir dir
                             :provider-backend "r2"
                             :r2-bucket "b" :r2-endpoint "https://r2"})]
    ((workflow/backend-advice) opts)
    (is (str/includes?
         (slurp (str (tools/tool-dir opts tools/infrastructure-tool)
                     "/backend.tf.json"))
         "p/alice-infrastructure.tfstate"))))

(deftest whole-build-renders-all-stages
  (let [dir (temp-dir)
        result (wf/run workflow/workflow
                       (assoc vt/base :green/event :build
                              :workdir dir :profile "built"))
        root (str dir "/built/")]
    (is (= 0 (:green/exit result)))
    (doseq [file ["alice-infrastructure/main.tf"
                  "alice-infrastructure/backend.tf.json"
                  "alice-ansible-local/main.yml"
                  "alice-ansible-remote/main.yml"
                  "alice-ansible-remote/inventory.json"
                  "alice-acceptance/acceptance.sh"]]
      (is (.exists (io/file (str root file))) file))))

(deftest dry-run-touches-nothing
  (let [dir (temp-dir)
        result (wf/run workflow/workflow
                       (assoc vt/base :green/event :create :green/dry-run true
                              :workdir dir :profile "dry"))]
    (is (= 0 (:green/exit result)))
    (is (empty? (seq (.listFiles (io/file dir)))))))

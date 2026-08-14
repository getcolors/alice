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

(deftest delete-removes-local-alias-before-droplet
  (is (= [:alice/ansible-local] (next-steps :delete :alice/start)))
  (is (= [:alice/infrastructure] (next-steps :delete :alice/ansible-local)))
  (is (= [:alice/generated-cleanup]
         (next-steps :delete :alice/infrastructure))))

(deftest sync-owns-download-copy-and-successful-cleanup
  (is (= [:alice/infrastructure] (next-steps :sync :alice/start)))
  (is (= [:alice/ansible-local] (next-steps :sync :alice/infrastructure)))
  (is (= [:alice/ansible-remote] (next-steps :sync :alice/ansible-local)))
  (is (= [:alice/sync] (next-steps :sync :alice/ansible-remote)))
  (is (= [:alice/sync-ansible-local-delete]
         (next-steps :sync :alice/sync)))
  (is (= [:alice/sync-infrastructure-delete]
         (next-steps :sync :alice/sync-ansible-local-delete)))
  (is (= [:alice/sync-generated-cleanup]
         (next-steps :sync :alice/sync-infrastructure-delete))))

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

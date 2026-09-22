(ns io.github.getcolors.alice.workflow-test
 (:require [clojure.test :refer [deftest is]] [clojure.java.io :as io]
           [green.workflow :as wf] [io.github.getcolors.alice.workflow :as workflow]
           [io.github.getcolors.alice.tools :as tools] [io.github.getcolors.alice.access :as access] [io.github.getcolors.alice.sync :as sync]
           [io.github.getcolors.compute-node :as compute]
           [io.github.getcolors.alice.validate-test :as vt]))
(defn- temp-dir [] (.toFile (java.nio.file.Files/createTempDirectory "alice-build-" (make-array java.nio.file.attribute.FileAttribute 0))))
(deftest delete-and-sync-order
 (is (= :alice/ssh-resource (second (workflow/wire-fn :alice/start {:green/event :delete}))))
 (is (= [tools/ansible-local-step :alice/infrastructure] (workflow/wire-fn :alice/ansible-local {:green/event :delete})))
 (is (= [tools/infrastructure-step :alice/registration-delete] (workflow/wire-fn :alice/infrastructure {:green/event :delete})))
 (is (= [sync/sync-step :alice/sync-ansible-local-delete] (workflow/wire-fn :alice/sync {:green/event :sync})))
 (is (= :alice/registration-delete (second (workflow/wire-fn :alice/sync-infrastructure-delete {:green/event :sync})))))
(deftest sync-relabels-delete-and-preserves-guard
 (with-redefs [compute/compute-node! (fn [opts req operation] (is (= "delete" operation)) (is (false? (:compute-prevent-destroy opts))) {:status "destroyed"})]
  (let [result (workflow/sync-infrastructure-delete-step (assoc vt/base :green/event :sync))]
   (is (= :sync (:green/event result))) (is (true? (:compute-prevent-destroy result))) (is (= 0 (:green/exit result))))))
(deftest failed-final-sync-prevents-destruction
 (let [calls (atom []) passthrough #(assoc % :green/exit 0)]
  (with-redefs [workflow/start-step passthrough access/resource-step passthrough access/registration-step passthrough access/agent-step passthrough tools/infrastructure-step passthrough tools/ansible-local-step passthrough tools/ansible-remote-step passthrough
                sync/sync-step #(assoc % :green/exit 1 :green/err "checksum failed")
                workflow/sync-local-delete-step #(do (swap! calls conj :config) %)
                workflow/sync-infrastructure-delete-step #(do (swap! calls conj :destroy) %)]
   (is (= 1 (:green/exit (wf/run workflow/workflow (assoc vt/base :green/event :sync)))))
   (is (empty? @calls)))))
(deftest inspection-failure-prevents-delete
 (with-redefs [compute/compute-node! (fn [_ req operation] (is (= "0" (:node_id req))) (is (= "inspect" operation)) {:status "error"})]
  (is (= 1 (:green/exit (tools/load-infrastructure-step (assoc vt/base :green/event :delete)))))))
(deftest whole-build-default-and-referenced-vpc
 (doseq [base [vt/base vt/discovery-base vt/keygen-base]]
  (let [dir (temp-dir)]
   (try
    (let [result (wf/run workflow/workflow (assoc base :green/event :build :workdir (str dir)))]
     (is (= 0 (:green/exit result)) (:green/err result))
     (doseq [file ["0/compute.tf.json" "0/backend.tf.json" "alice-ansible-local/main.yml" "alice-ansible-remote/main.yml" "alice-acceptance/acceptance.sh"]]
      (is (.isFile (io/file dir "build" (:profile base) file)) file)))
    (finally (doseq [file (reverse (file-seq dir))] (.delete file)))))))
(deftest dry-run-touches-nothing
 (let [dir (temp-dir)]
  (try
   (is (= 0 (:green/exit (wf/run workflow/workflow (assoc vt/base :green/event :create :green/dry-run true :workdir (str dir))))))
   (is (empty? (seq (.listFiles dir))))
   (finally (.delete dir)))))

(deftest explicit-empty-ssh-sources-never-widen-access
  (is (thrown? Exception (io.github.getcolors.alice.compute/plan (assoc vt/base :alice-ssh-sources [])))))

(deftest retired-resumes-only-idempotent-local-cleanup
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "alice-retired-" (make-array java.nio.file.attribute.FileAttribute 0)))
        opts {:profile "retired" :workdir (str dir) :green/event :delete}
        paths [(io/file (tools/tool-dir opts tools/acceptance-tool) "acceptance.sh")]
        keep (io/file dir "keep") seen (atom []) inspection-exit (atom 0)
        native (wf/workflow {:start :alice/start :next-fn workflow/next-steps
          :wire-fn (fn [step current]
            (case step
              :alice/start [(fn [o] (swap! seen conj step) (assoc o :green/exit 0)) :alice/load-infrastructure]
              :alice/load-infrastructure [(fn [o] (swap! seen conj step) (assoc o :green/exit @inspection-exit :alice/already-destroyed true)) :forbidden/remote]
              :alice/registration-delete [(fn [o] (swap! seen conj step) o) :alice/generated-cleanup]
              :alice/generated-cleanup [(fn [o] (swap! seen conj step) ((first (workflow/wire-fn step o)) o))]
              [(fn [_] (throw (ex-info "unexpected remote stage" {:step step})))]))})]
    (try
      (doseq [path paths] (io/make-parents path) (spit path "synthetic leftover"))
      (spit keep "unrelated")
      (dotimes [_ 2]
        (reset! seen [])
        (is (zero? (:green/exit (wf/run native opts))))
        (is (= [:alice/start :alice/load-infrastructure :alice/registration-delete :alice/generated-cleanup] @seen))
        (is (every? #(not (.exists %)) paths))
        (is (= "unrelated" (slurp keep))))
      (reset! inspection-exit 1) (reset! seen [])
      (is (= 1 (:green/exit (wf/run native opts))))
      (is (= [:alice/start :alice/load-infrastructure] @seen))
      (is (= [] (workflow/next-steps :alice/load-infrastructure [:forbidden/remote] (assoc opts :green/exit 1 :alice/already-destroyed true))))
      (is (not (.exists (io/file dir ".ssh"))))
      (finally (doseq [f (reverse (file-seq dir))] (io/delete-file f true))))))

(deftest generated-cleanup-retains-compute-root
  (let [dir (temp-dir) opts (assoc vt/base :green/event :delete :workdir (str dir))
        root (io/file dir (:profile opts) "0")]
    (try
      (.mkdirs root)
      (doseq [name ["compute.tf.json" "backend.tf.json" ".terraform.lock.hcl"]]
        (spit (io/file root name) "retained"))
      (.mkdirs (io/file root ".terraform"))
      (tools/generated-cleanup-step opts)
      (is (every? #(.exists (io/file root %)) ["compute.tf.json" "backend.tf.json" ".terraform.lock.hcl" ".terraform"]))
      (finally (doseq [file (reverse (file-seq dir))] (.delete file))))))

(deftest repeated-delete-uses-only-confirmed-empty-inspection
  (with-redefs [compute/compute-node! (fn [_ req operation]
                                      (is (= "inspect" operation))
                                      (is (= "alice-node-0.tfstate" (:state_filename req)))
                                      {:status "destroyed" :directory "/sdk/alice-test/0"})]
    (let [result (tools/load-infrastructure-step (assoc vt/base :green/event :delete))]
      (is (zero? (:green/exit result)))
      (is (true? (:alice/already-destroyed result)))
      (is (= [[:alice/registration-delete result]]
             (workflow/next-steps :alice/load-infrastructure [:alice/ansible-local] result))))))

(deftest parallel-compute-failure-still-cleans-scoped-agent
  (let [calls (atom []) ready (promise)
        passthrough #(assoc % :green/exit 0)]
    (with-redefs [workflow/start-step passthrough
                  access/resource-step passthrough
                  access/registration-step passthrough
                  access/agent-step (fn [opts]
                                      (access/*register!* :resource #(swap! calls conj :agent-stopped))
                                      (swap! calls conj :agent-started)
                                      (deliver ready true)
                                      (passthrough opts))
                  tools/infrastructure-step (fn [opts]
                                              (deref ready 5000 false)
                                              (swap! calls conj :compute-failed)
                                              (assoc opts :green/exit 1))
                  tools/ansible-local-step (fn [opts] (swap! calls conj :unexpected-ansible) opts)]
      (let [result (access/scoped #(wf/run workflow/workflow (assoc vt/base :green/event :create)))]
        (is (= 1 (:green/exit result)))
        (is (= [:agent-started :compute-failed :agent-stopped] @calls))))))

(deftest missing-ssh-authority-stops-before-registration-and-compute
  (let [calls (atom [])]
    (with-redefs [workflow/start-step #(assoc % :green/exit 0)
                  io.github.getcolors.compute-ssh/ssh-resource! (fn [& _] {:status "error"})
                  access/registration-step #(do (swap! calls conj :registration) %)
                  tools/infrastructure-step #(do (swap! calls conj :compute) %)]
      (is (= 1 (:green/exit (access/scoped #(wf/run workflow/workflow (assoc vt/base :green/event :create))))))
      (is (empty? @calls)))))

(deftest compute-delete-does-not-delete-the-ssh-resource
  (let [calls (atom []) passthrough #(assoc % :green/exit 0)]
    (with-redefs [workflow/start-step passthrough
                  io.github.getcolors.compute-ssh/ssh-resource!
                  (fn [_ _ operation _] (swap! calls conj operation) io.github.getcolors.alice.compute/placeholder-resource)
                  tools/load-infrastructure-step passthrough
                  tools/ansible-local-step #(do (swap! calls conj :alias-removed) %)
                  tools/infrastructure-step #(do (swap! calls conj :compute-destroyed) %)
                  access/registration-delete-step #(do (swap! calls conj :registration-destroyed) %)
                  tools/generated-cleanup-step passthrough]
      (is (zero? (:green/exit (wf/run workflow/workflow (assoc vt/base :green/event :delete)))))
      (is (= ["inspect" :alias-removed :compute-destroyed :registration-destroyed] @calls)))))

(deftest credential-free-build-preserves-live-runtime-authority-and-templates
  (let [dir (temp-dir)
        runtime (io/file dir (:profile vt/base))
        node (io/file runtime "0" "compute.tf.json")
        registration (io/file runtime "registration-app-access" "compute.tf.json")
        authority (io/file runtime "ssh" "app-access" "resource.json")
        files [node registration authority]]
    (try
      (doseq [file files] (io/make-parents file) (spit file "runtime ownership must remain untouched"))
      (let [result (wf/run workflow/workflow (assoc vt/base :workdir (str dir) :green/event :build))]
        (is (zero? (:green/exit result)) (:green/err result))
        (is (every? #(= "runtime ownership must remain untouched" (slurp %)) files))
        (is (.exists (io/file dir "build" (:profile vt/base) "0" "compute.tf.json")))
        (is (.exists (io/file dir "build" (:profile vt/base) "alice-ansible-remote" "inventory.json"))))
      (finally (doseq [file (reverse (file-seq dir))] (.delete file))))))

(deftest existing-runtime-consumer-refuses-new-ssh-authority
  (let [dir (temp-dir)
        template (io/file dir (:profile vt/base) "0" "compute.tf.json")
        operations (atom [])]
    (try
      (io/make-parents template)
      (spit template "must never be parsed as desired state")
      (with-redefs [io.github.getcolors.compute-ssh/ssh-resource!
                    (fn [_ _ operation _] (swap! operations conj operation)
                      {:status "error" :error {:message "encrypted SSH authority missing; recover explicitly"}})]
        (doseq [event [:create :sync]]
          (let [result (access/resource-step (assoc vt/base :workdir (str dir) :green/event event))]
            (is (= 1 (:green/exit result)))
            (is (= "encrypted SSH authority missing; recover explicitly" (:green/err result)))))
        (is (= ["inspect" "inspect"] @operations)))
      (finally (doseq [file (reverse (file-seq dir))] (.delete file))))))

(ns io.github.getcolors.alice.workflow-test
 (:require [clojure.test :refer [deftest is]] [clojure.java.io :as io]
           [green.workflow :as wf] [io.github.getcolors.alice.workflow :as workflow]
           [io.github.getcolors.alice.tools :as tools] [io.github.getcolors.alice.sync :as sync]
           [io.github.getcolors.compute-orchestration :as compute]
           [io.github.getcolors.compute-inspection :as inspection]
           [io.github.getcolors.alice.validate-test :as vt]))
(defn- temp-dir [] (.toFile (java.nio.file.Files/createTempDirectory "alice-build-" (make-array java.nio.file.attribute.FileAttribute 0))))
(deftest delete-and-sync-order
 (is (= :alice/load-infrastructure (second (workflow/wire-fn :alice/start {:green/event :delete}))))
 (is (= [tools/ansible-local-step :alice/infrastructure] (workflow/wire-fn :alice/ansible-local {:green/event :delete})))
 (is (= [tools/infrastructure-step :alice/generated-cleanup] (workflow/wire-fn :alice/infrastructure {:green/event :delete})))
 (is (= [sync/sync-step :alice/sync-ansible-local-delete] (workflow/wire-fn :alice/sync {:green/event :sync})))
 (is (= :alice/sync-generated-cleanup (second (workflow/wire-fn :alice/sync-infrastructure-delete {:green/event :sync})))))
(deftest sync-relabels-delete-and-preserves-guard
 (with-redefs [compute/orchestrate (fn [opts & _] (is (= :delete (:green/event opts))) (is (false? (:compute-prevent-destroy opts))) {:status "destroyed"})]
  (let [result (workflow/sync-infrastructure-delete-step (assoc vt/base :green/event :sync))]
   (is (= :sync (:green/event result))) (is (true? (:compute-prevent-destroy result))) (is (= 0 (:green/exit result))))))
(deftest failed-final-sync-prevents-destruction
 (let [calls (atom []) passthrough #(assoc % :green/exit 0)]
  (with-redefs [workflow/start-step passthrough tools/infrastructure-step passthrough tools/ansible-local-step passthrough tools/ansible-remote-step passthrough
                sync/sync-step #(assoc % :green/exit 1 :green/err "checksum failed")
                workflow/sync-local-delete-step #(do (swap! calls conj :config) %)
                workflow/sync-infrastructure-delete-step #(do (swap! calls conj :destroy) %)]
   (is (= 1 (:green/exit (wf/run workflow/workflow (assoc vt/base :green/event :sync)))))
   (is (empty? @calls)))))
(deftest inspection-failure-prevents-delete
 (with-redefs [inspection/read-deployment (fn [_ env deps _] (is (map? env)) (is (contains? env "HOME")) (is (= {} deps)) {:status "error"})]
  (is (= 1 (:green/exit (tools/load-infrastructure-step (assoc vt/base :green/event :delete)))))))
(deftest whole-build-default-and-referenced-vpc
 (doseq [base [vt/base vt/discovery-base vt/keygen-base]]
  (let [dir (temp-dir)]
   (try
    (let [result (wf/run workflow/workflow (assoc base :green/event :build :workdir (str dir)))]
     (is (= 0 (:green/exit result)) (:green/err result))
     (doseq [file ["alice-infrastructure/nodes/0/node.tf.json" "alice-infrastructure/shared/backend.tf.json" "alice-ansible-local/main.yml" "alice-ansible-remote/main.yml" "alice-acceptance/acceptance.sh"]]
      (is (.isFile (io/file dir (:profile base) file)) file)))
    (finally (doseq [file (reverse (file-seq dir))] (.delete file)))))))
(deftest dry-run-touches-nothing
 (let [dir (temp-dir)]
  (try
   (is (= 0 (:green/exit (wf/run workflow/workflow (assoc vt/base :green/event :create :green/dry-run true :workdir (str dir))))))
   (is (empty? (seq (.listFiles dir))))
   (finally (.delete dir)))))

(deftest explicit-empty-ssh-sources-never-widen-access
  (is (thrown? Exception
        (io.github.getcolors.compute-planning/plan-deployment
          (assoc vt/base :alice-ssh-sources [])
          (io.github.getcolors.alice.compute/topology vt/base)
          (io.github.getcolors.alice.compute/requirements (assoc vt/base :alice-ssh-sources []))))))

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
              :alice/generated-cleanup [(fn [o] (swap! seen conj step) ((first (workflow/wire-fn step o)) o))]
              [(fn [_] (throw (ex-info "unexpected remote stage" {:step step})))]))})]
    (try
      (doseq [path paths] (io/make-parents path) (spit path "synthetic leftover"))
      (spit keep "unrelated")
      (dotimes [_ 2]
        (reset! seen [])
        (is (zero? (:green/exit (wf/run native opts))))
        (is (= [:alice/start :alice/load-infrastructure :alice/generated-cleanup] @seen))
        (is (every? #(not (.exists %)) paths))
        (is (= "unrelated" (slurp keep))))
      (reset! inspection-exit 1) (reset! seen [])
      (is (= 1 (:green/exit (wf/run native opts))))
      (is (= [:alice/start :alice/load-infrastructure] @seen))
      (is (= [] (workflow/next-steps :alice/load-infrastructure [:forbidden/remote] (assoc opts :green/exit 1 :alice/already-destroyed true))))
      (is (not (.exists (io/file dir ".ssh"))))
      (finally (doseq [f (reverse (file-seq dir))] (io/delete-file f true))))))

(ns io.github.getcolors.alice.ssh-test
  "The machine keypair, per standards/ssh-keypair.md.

  Nothing here may write to the operator's real `~/.ssh`. The generating branch
  of the create matrix is therefore exercised only through the failure rows,
  which decide before any file is created; generation itself belongs to ONCE's
  own suite, where the home directory is redirected."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.alice.ssh :as ssh]
            [io.github.getcolors.alice.validate-test :as vt]))

(def keygen (assoc vt/keygen-base :green/event :create))
(def optout (assoc vt/base :green/event :create))

(deftest presence-of-an-explicit-key-is-the-only-switch
  (is (true? (ssh/keygen? keygen)))
  (is (false? (ssh/keygen? optout))))

(deftest build-and-dry-run-render-a-placeholder-home
  ;; §6: a build must be byte-identical on every workstation, so the rendered
  ;; paths may not name the operator's home directory. Both the build event and
  ;; a dry-run are held to this — a dry-run is a create that touches nothing.
  (doseq [opts [(assoc vt/keygen-base :green/event :build)
                (assoc vt/keygen-base :green/event :create :green/dry-run true)]]
    (let [result (ssh/with-machine-key opts)]
      (is (= (str ssh/build-placeholder-dir "/alice-test")
             (:ssh-private-key-path result)))
      (is (= (str ssh/build-placeholder-dir "/alice-test.pub")
             (:ssh-public-key-path result)))
      (is (not (str/includes? (:ssh-public-key-path result)
                              (System/getProperty "user.home")))))))

(deftest opt-out-opts-pass-through-untouched
  ;; §1: when desired state supplies the key, the package must not generate,
  ;; validate, or delete key material, and must render as it did before the
  ;; standard.
  (let [result (ssh/with-machine-key (assoc optout :green/event :build))]
    (is (= "812184" (:digitalocean-ssh-keys result)))
    (is (nil? (:ssh-private-key-path result)))
    (is (nil? (:ssh-keygen result)))))

(deftest keygen-marks-itself-so-the-mode-stays-sticky
  ;; `with-machine-key` fills the provider key with the generated path, which
  ;; would flip the desired-state test to opt-out for the rest of the run.
  (let [result (ssh/with-machine-key (assoc vt/keygen-base :green/event :build))]
    (is (true? (:ssh-keygen result)))
    (is (true? (ssh/keygen? result)))))

(deftest state-without-a-key-refuses-rather-than-regenerating
  ;; §3.1 row two: compute state exists but this workstation has no key. A
  ;; regenerated key cannot reach the running Droplet, so this is an error and
  ;; never a silent regeneration. The profile is one no workstation will hold.
  (let [opts (assoc vt/keygen-base :green/event :create
                    :profile "alice-test-absent-key-fixture")
        result (ssh/ensure-key! opts (constantly {:ip "203.0.113.10"}))]
    (is (= 1 (:green/exit result)))
    (is (str/includes? (:green/err result) "does not hold the machine key"))))

(deftest opt-out-never-runs-the-create-matrix
  (let [result (ssh/ensure-key! optout (constantly {:ip "203.0.113.10"}))]
    (is (nil? (:green/exit result)))))

(deftest cleanup-runs-only-on-a-delete-in-keygen-mode
  ;; §3.3: the delete DAG wires this after the compute destroy, so reaching it
  ;; means the destroy succeeded. Any other event, or opt-out mode, is a no-op —
  ;; the package must not remove key material it does not own.
  (is (= 0 (:green/exit (ssh/cleanup-step (assoc vt/base :green/event :delete)))))
  (is (= 0 (:green/exit (ssh/cleanup-step (assoc vt/keygen-base :green/event :sync)))))
  (is (= 0 (:green/exit (ssh/cleanup-step (assoc vt/keygen-base :green/event :create))))))

(deftest key-paths-are-named-after-the-profile
  ;; §2: the profile is globally unique by construction, because it already
  ;; keys remote state, which is what makes one flat `~/.ssh` safe.
  (is (str/ends-with? (ssh/private-key-path vt/keygen-base) "/.ssh/alice-test"))
  (is (str/ends-with? (ssh/public-key-path vt/keygen-base) "/.ssh/alice-test.pub")))

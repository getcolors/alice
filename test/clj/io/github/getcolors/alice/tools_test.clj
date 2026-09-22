(ns io.github.getcolors.alice.tools-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.scaffold :as sc]
            [io.github.getcolors.alice.tools :as tools]
            [io.github.getcolors.alice.validate-test :as vt]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "alice-test-" "")]
    (.delete f) (.mkdirs f) (str f)))

(deftest stage-names-are-package-specific
  (is (= "alice-infrastructure" tools/infrastructure-tool))
  (is (= "alice-ansible-remote" tools/ansible-remote-tool)))

(deftest inventory-targets-one-root-host
  (let [parsed (json/parse-string
                (tools/inventory
                 (assoc vt/base :profile "demo" :colors-compute/node {:node_id "0" :provider "digitalocean" :name "demo" :ip "203.0.113.10" :user "root" :sudoer "root"})))]
    (is (= "203.0.113.10"
           (get-in parsed ["all" "hosts" "demo" "ansible_host"])))
    (is (= "root"
           (get-in parsed ["all" "hosts" "demo" "ansible_user"])))
    (is (= "'-o' 'ControlMaster=no' '-o' 'ControlPersist=no' '-S' 'none'"
           (get-in parsed ["all" "hosts" "demo" "ansible_ssh_common_args"])))))

(deftest keygen-inventory-names-the-machine-key
  ;; `ansible.cfg` connects with `-F /dev/null`, so the `IdentityFile` in the
  ;; managed `~/.ssh/config` block never reaches the remote stage. Without this
  ;; the run offers no identity at all and fails `Permission denied
  ;; (publickey)` on every workstation whose agent does not already hold the
  ;; generated key.
  (let [parsed (json/parse-string
                (tools/inventory
                 (assoc vt/keygen-base :profile "demo" :colors-compute/node {:node_id "0" :provider "digitalocean" :name "demo" :ip "203.0.113.10" :user "root" :sudoer "root"}
                        :ssh-private-key-path "/home/op/.ssh/demo")))]
    (is (= "/home/op/.ssh/demo"
           (get-in parsed ["all" "hosts" "demo"
                           "ansible_ssh_private_key_file"])))
    ;; And offers that key alone. Without `IdentitiesOnly` the agent's keys go
    ;; first, and stale copies of superseded machine keys — banked by
    ;; `AddKeysToAgent`, outliving the deleted file — exhaust `MaxAuthTries`
    ;; as `Too many authentication failures` before the named key is tried.
    ;; `IdentityAgent none` also keeps this run from banking another copy.
    (is (= "'-i' '/home/op/.ssh/demo' '-o' 'IdentitiesOnly=yes' '-o' 'IdentityAgent=none' '-o' 'ForwardAgent=no' '-o' 'ControlMaster=no' '-o' 'ControlPersist=no' '-S' 'none'"
           (get-in parsed ["all" "hosts" "demo"
                           "ansible_ssh_common_args"])))))

(deftest remote-render-installs-transmission-and-keeps-ui-private
  (let [dir (temp-dir)
        opts (assoc vt/base :workdir dir :profile "render" :green/event :build)
        result (tools/ansible-remote-step opts)
        play (slurp (str (tools/tool-dir result tools/ansible-remote-tool)
                         "/main.yml"))]
    (is (str/includes? play "transmission-daemon"))
    (is (str/includes? play "aa-disable /usr/bin/transmission-daemon"))
    (is (str/includes? play "rpc-bind-address"))
    (is (str/includes? play "rpc-authentication-required"))
    (is (str/includes? play "127.0.0.1"))
    (is (str/includes? play "/var/lib/transmission-daemon/downloads"))))

(deftest acceptance-renders-a-real-ssh-tunnel-probe
  (let [dir (temp-dir)
        opts (assoc vt/base :workdir dir :profile "render" :green/event :build)
        result (tools/acceptance-step opts)
        script (slurp (str (tools/tool-dir result tools/acceptance-tool)
                           "/acceptance.sh"))]
    (is (str/includes? script "-N -L"))
    (is (str/includes? script
                       "127.0.0.1:${local_port}:127.0.0.1:${remote_port}"))
    (is (str/includes? script "/transmission/web/"))))

(deftest workdir-resolves-beside-state
  (is (= "/srv/project/.colors/p/alice-infrastructure"
         (tools/tool-dir {:workdir ".colors" :profile "p"
                          :green/state-file "/srv/project/colors.yml"}
                         tools/infrastructure-tool))))

(deftest local-play-receives-its-required-node-fields
  (with-redefs [green.ansible/ansible-with-spec
                (fn [opts config _]
                  (is (= [{:name "alice-test" :ip "192.0.2.10" :user "root"}]
                         (get-in config [:extra-vars :ssh_hosts])))
                  (is (= "alice" (get-in config [:extra-vars :ssh_legacy_marker_prefix]))) opts)]
    (tools/ansible-local-step (assoc vt/base :green/event :build))))

(deftest compute-json-accepts-library-mixed-key-maps
  (is (= {"backups" true "region" "ams"}
         (cheshire.core/parse-string
          (#'io.github.getcolors.alice.tools/compute-json {:region "ams" "backups" true} 0)))))

(deftest compute-startup-error-is-actionable
  (let [failure {:status "error"
                 :error {:code "command_failed" :stage "init"
                         :message "Required command failed."
                         :command ["tofu" "init"]
                         :executable "/home/operator/.asdf/shims/tofu"
                         :exit_code 126 :stderr "No version is set for command tofu"
                         :infrastructure_changes "none"}}]
    (with-redefs [io.github.getcolors.compute-node/compute-node! (fn [& _] failure)]
      (let [result (tools/infrastructure-step (assoc vt/base :green/event :sync))
            message (:green/err result)]
        (is (= 1 (:green/exit result)))
        (is (str/includes? message "Could not start OpenTofu: `tofu init` exited with code 126."))
        (is (str/includes? message "Executable: /home/operator/.asdf/shims/tofu"))
        (is (str/includes? message "No version is set for command tofu"))
        (is (str/includes? message "direnv exec . ./green sync"))
        (is (str/includes? message "No infrastructure changes were made by this operation."))
        (is (not (str/includes? message "ownership")))))))

(deftest compute-apply-failure-never-claims-no-changes
  (with-redefs [io.github.getcolors.compute-node/compute-node!
                (fn [& _] {:status "error" :error {:code "command_failed" :stage "apply"
                  :command ["tofu" "apply"] :exit_code 1 :stderr "Provider request failed."
                  :infrastructure_changes "possible"}})]
    (let [message (:green/err (tools/infrastructure-step (assoc vt/base :green/event :create)))]
      (is (str/includes? message "`tofu apply` exited with code 1"))
      (is (str/includes? message "Infrastructure changes may have occurred."))
      (is (not (str/includes? message "No infrastructure changes")))
      (is (not (str/includes? message "direnv"))))))

(deftest compute-inspection-preserves-state-error
  (with-redefs [io.github.getcolors.compute-node/compute-node!
                (fn [& _] {:status "error" :error {:code "state_unreadable" :stage "state"
                  :message "Compute state could not be read." :infrastructure_changes "none"}})]
    (let [message (:green/err (tools/load-infrastructure-step (assoc vt/base :green/event :delete)))]
      (is (str/includes? message "Compute state could not be read."))
      (is (not (str/includes? message "ownership"))))))

(deftest unknown-compute-errors-never-expose-exception-or-claim-safety
  (doseq [f [(fn [& _] {:status "error"})
            (fn [& _] (throw (ex-info "secret-provider-response" {})))]]
    (with-redefs [io.github.getcolors.compute-node/compute-node! f]
      (let [message (:green/err (tools/infrastructure-step (assoc vt/base :green/event :create)))]
        (is (= "Compute operation failed; no diagnostic details were returned." message))
        (is (not (str/includes? message "secret-provider-response")))))))

(deftest compute-missing-credential-names-the-variable
  (with-redefs [io.github.getcolors.compute-node/compute-node!
                (fn [& _] {:status "error" :error {:code "missing_credentials" :stage "credentials"
                  :message "Required credentials are not set." :credential "COLORS_PAR_DO_TOKEN"
                  :infrastructure_changes "none"}})]
    (let [message (:green/err (tools/infrastructure-step (assoc vt/base :green/event :sync)))]
      (is (str/includes? message "Stage: credentials"))
      (is (str/includes? message "Required credential: COLORS_PAR_DO_TOKEN"))
      (is (not (str/includes? message "direnv"))))))

(deftest compute-cancellation-propagates
  (with-redefs [io.github.getcolors.compute-node/compute-node!
                (fn [& _] (throw (InterruptedException. "cancelled")))]
    (doseq [step [tools/infrastructure-step tools/load-infrastructure-step]]
      (is (thrown? InterruptedException (step (assoc vt/base :green/event :create)))))))

(deftest acceptance-timeout-stops-the-owned-foreground-tunnel
  (let [dir (temp-dir)
        bin (clojure.java.io/file dir "bin")
        pid (clojure.java.io/file dir "tunnel.pid")
        opts (assoc vt/base :workdir dir :profile "timeout" :green/event :build)]
    (try
      (.mkdirs bin)
      (doseq [[name body]
              [["ssh" "#!/bin/sh\ncase \"$*\" in\n *systemctl*) echo active; exit 0;;\n *' -O check '*) exit 0;;\nesac\necho $$ > \"$ALICE_TEST_TUNNEL_PID\"\nexec sleep 60\n"]
               ["curl" "#!/bin/sh\nexec sleep 60\n"]]]
        (let [file (clojure.java.io/file bin name)]
          (spit file body) (.setExecutable file true)))
      (tools/acceptance-step opts)
      (let [result (green.process/run-with-timeout
                    ["bash" (str (tools/tool-dir opts tools/acceptance-tool) "/acceptance.sh")]
                    {:extra-env {"PATH" (str bin ":" (System/getenv "PATH"))
                                 "ALICE_TEST_TUNNEL_PID" (str pid)}} 1000)]
        (is (= 124 (:exit result)))
        (is (.exists pid))
        (when (.exists pid)
          (let [child (str/trim (slurp pid))]
            (is (not (zero? (:exit (green.process/run ["kill" "-0" child]))))))))
      (finally
        (doseq [file (reverse (file-seq (clojure.java.io/file dir)))] (.delete file))))))

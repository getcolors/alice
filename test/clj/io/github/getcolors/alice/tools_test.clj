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
                 (assoc vt/base :profile "demo" :colors-compute/cluster {:nodes [{:node_id "0" :provider "digitalocean" :name "demo" :ip "203.0.113.10" :user "root" :sudoer "root"}]})))]
    (is (= "203.0.113.10"
           (get-in parsed ["all" "hosts" "demo" "ansible_host"])))
    (is (= "root"
           (get-in parsed ["all" "hosts" "demo" "ansible_user"])))
    ;; Opt-out mode: the operator supplied the key, so how ansible finds it is
    ;; the operator's business and the inventory says nothing about it — not
    ;; the key path, and not any agent bypass either.
    (is (not (contains? (get-in parsed ["all" "hosts" "demo"])
                        "ansible_ssh_private_key_file")))
    (is (not (contains? (get-in parsed ["all" "hosts" "demo"])
                        "ansible_ssh_common_args")))))

(deftest keygen-inventory-names-the-machine-key
  ;; `ansible.cfg` connects with `-F /dev/null`, so the `IdentityFile` in the
  ;; managed `~/.ssh/config` block never reaches the remote stage. Without this
  ;; the run offers no identity at all and fails `Permission denied
  ;; (publickey)` on every workstation whose agent does not already hold the
  ;; generated key.
  (let [parsed (json/parse-string
                (tools/inventory
                 (assoc vt/keygen-base :profile "demo" :colors-compute/cluster {:nodes [{:node_id "0" :provider "digitalocean" :name "demo" :ip "203.0.113.10" :user "root" :sudoer "root"}]}
                        :ssh-private-key-path "/home/op/.ssh/demo")))]
    (is (= "/home/op/.ssh/demo"
           (get-in parsed ["all" "hosts" "demo"
                           "ansible_ssh_private_key_file"])))
    ;; And offers that key alone. Without `IdentitiesOnly` the agent's keys go
    ;; first, and stale copies of superseded machine keys — banked by
    ;; `AddKeysToAgent`, outliving the deleted file — exhaust `MaxAuthTries`
    ;; as `Too many authentication failures` before the named key is tried.
    ;; `IdentityAgent none` also keeps this run from banking another copy.
    (is (= "-o IdentitiesOnly=yes -o IdentityAgent=none"
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
    (is (str/includes? script "-fN -L"))
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

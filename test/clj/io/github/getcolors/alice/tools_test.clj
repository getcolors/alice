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
                 (assoc vt/base :profile "demo" :ip "203.0.113.10")))]
    (is (= "203.0.113.10"
           (get-in parsed ["all" "hosts" "demo" "ansible_host"])))
    (is (= "root"
           (get-in parsed ["all" "hosts" "demo" "ansible_user"])))))

(deftest infrastructure-renders-droplet-with-guard-and-no-token
  (let [dir (temp-dir)
        opts (assoc vt/base :workdir dir :profile "render" :green/event :build)]
    (sc/scaffold opts (tools/infrastructure-specs opts))
    (let [hcl (slurp (str (tools/tool-dir opts tools/infrastructure-tool)
                           "/main.tf"))]
      (is (str/includes? hcl "resource \"digitalocean_droplet\" \"alice\""))
      (is (str/includes? hcl "prevent_destroy = true"))
      (is (str/includes? hcl "vpc_uuid = \"00000000-0000-4000-8000-000000000000\""))
      (is (not (str/includes? hcl "COLORS_PAR_DO_TOKEN")))
      (is (not (str/includes? hcl "fixture-secret"))))))

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
    (is (str/includes? play "127.0.0.1"))))

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

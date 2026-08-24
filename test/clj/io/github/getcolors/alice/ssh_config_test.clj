(ns io.github.getcolors.alice.ssh-config-test
  "The `~/.ssh/config` block, per standards/ssh-config.md.

  Only the pure line functions are exercised: `adopt-error` and
  `placement-error` read the operator's real config file, and a test must not
  depend on what is in it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.alice.ssh-config :as ssh-config]
            [io.github.getcolors.alice.validate-test :as vt]))

(defn- lines [s] (str/split-lines s))

(deftest the-alias-is-the-profile
  (is (= "alice-test" (ssh-config/host-alias vt/base)))
  (is (= "~/.ssh/alice-test" (ssh-config/identity-file vt/base))))

(deftest the-identity-file-keeps-its-tilde
  ;; OpenSSH expands it, and leaving it unexpanded is what keeps the rendered
  ;; block identical on every workstation.
  (is (not (str/includes? (ssh-config/identity-file vt/base)
                          (System/getProperty "user.home")))))

(deftest the-marker-carries-the-alias-alone
  ;; §2: the profile is <package>-<suffix>, so it already names the package.
  (is (= "# BEGIN alice-test ANSIBLE MANAGED BLOCK"
         (ssh-config/begin-marker "alice-test"))))

(deftest our-own-block-is-not-mistaken-for-a-foreign-stanza
  (let [config (str "# BEGIN alice-test ANSIBLE MANAGED BLOCK\n"
                    "Host alice-test\n"
                    "    HostName 203.0.113.10\n"
                    "# END alice-test ANSIBLE MANAGED BLOCK\n")]
    (is (nil? (ssh-config/foreign-stanza-line (lines config) "alice-test")))))

(deftest the-superseded-marker-still-counts-as-ours
  ;; §8: a check that knows only the new marker would read alice's own old
  ;; block as a hand-written stanza and refuse the migration meant to clean it
  ;; up. This is the failure the reference implementation hit on the converge
  ;; after its own marker changed.
  (let [config (str "# BEGIN alice alice-test ANSIBLE MANAGED BLOCK\n"
                    "Host alice-test\n"
                    "    HostName 203.0.113.10\n"
                    "# END alice alice-test ANSIBLE MANAGED BLOCK\n")]
    (is (nil? (ssh-config/foreign-stanza-line (lines config) "alice-test")))))

(deftest a-hand-written-stanza-stops-the-run
  ;; §5: it may be the operator's only record of how to reach something.
  (let [config (str "Host other\n"
                    "    HostName 198.51.100.4\n"
                    "\n"
                    "Host alice-test\n"
                    "    HostName 203.0.113.99\n")]
    (is (= 4 (ssh-config/foreign-stanza-line (lines config) "alice-test")))))

(deftest a-stanza-naming-the-alias-among-others-is-still-foreign
  (let [config "Host staging alice-test\n    HostName 203.0.113.99\n"]
    (is (= 1 (ssh-config/foreign-stanza-line (lines config) "alice-test")))))

(deftest options-above-the-first-host-line-are-detected
  ;; §5: they are global, and a BOF insert would capture them into this
  ;; deployment's stanza, narrowing a global setting without saying so.
  (let [config (str "# a comment\n"
                    "\n"
                    "ServerAliveInterval 60\n"
                    "\n"
                    "Host something\n")]
    (is (= 3 (ssh-config/leading-option-line (lines config)))))
  (is (nil? (ssh-config/leading-option-line
             (lines "# comment\nHost something\n    User root\n"))))
  (is (nil? (ssh-config/leading-option-line (lines "Match host x\n    User root\n"))))
  (is (nil? (ssh-config/leading-option-line (lines "")))))

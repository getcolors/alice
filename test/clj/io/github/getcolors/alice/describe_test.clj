(ns io.github.getcolors.alice.describe-test
  (:require [clojure.test :refer [deftest is]]
            [io.github.getcolors.alice.describe :as describe]
            [io.github.getcolors.alice.validate-test :as vt]))

(deftest describe-reports-reachable-transmission-host
  (let [config (str "# BEGIN alice alice-test ANSIBLE MANAGED BLOCK\n"
                    "Host alice-test\n"
                    "    HostName 203.0.113.10\n"
                    "# END alice alice-test ANSIBLE MANAGED BLOCK\n")
        runner (fn [args]
                 (if (some #{"systemctl"} args)
                   {:exit 0 :out "active\n" :err ""}
                   {:exit 0 :out "" :err ""}))
        report (describe/describe-report vt/base runner (constantly config))]
    (is (true? (get-in report [:ssh :present?])))
    (is (= "203.0.113.10" (get-in report [:ssh :host])))
    (is (true? (get-in report [:ssh :reachable?])))
    (is (true? (get-in report [:transmission :active?])))))

(deftest describe-skips-remote-checks-without-local-alias
  (let [calls (atom 0)
        report (describe/describe-report
                vt/base (fn [_] (swap! calls inc) {:exit 0}) (constantly ""))]
    (is (false? (get-in report [:ssh :present?])))
    (is (= "not checked" (get-in report [:transmission :status])))
    (is (zero? @calls))))

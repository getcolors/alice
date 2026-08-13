(ns io.github.getcolors.alice.operator-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.alice.operator :as operator]))

(defn- temp-dir []
  (let [f (java.io.File/createTempFile "alice-test-" "")]
    (.delete f) (.mkdirs f) (str f)))

(deftest command-opens-only-a-loopback-tunnel
  (let [command (operator/command {:profile "alice-digitalocean"
                                   :transmission-rpc-port 9091}
                                  19091)]
    (is (= "ssh" (first command)))
    (is (some #{"127.0.0.1:19091:127.0.0.1:9091"} command))
    (is (= "alice-digitalocean" (last command)))))

(deftest run-dispatches-a-valid-port
  (let [file (str (temp-dir) "/colors.yml")
        called (atom nil)]
    (spit file "profile: demo\ntransmission-rpc-port: 9091\n")
    (let [result (operator/run file ["18080"]
                               (fn [args] (reset! called args) {:exit 0}) {})]
      (is (= 0 (:green/exit result)))
      (is (some #{"127.0.0.1:18080:127.0.0.1:9091"} @called)))))

(deftest run-refuses-profile-overlay-and-invalid-port
  (let [file (str (temp-dir) "/colors.yml")]
    (spit file "profile: demo\n")
    (is (= 2 (:green/exit
              (operator/run file [] (fn [_] {:exit 0})
                            {"COLORS_PAR_PROFILE" "other"}))))
    (let [result (operator/run file ["70000"] (fn [_] {:exit 0}) {})]
      (is (= 2 (:green/exit result)))
      (is (str/includes? (:green/err result) "local port")))))

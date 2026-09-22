(ns io.github.getcolors.alice.ssh-test
 (:require [clojure.test :refer [deftest is]] [io.github.getcolors.alice.ssh :as ssh]
           [io.github.getcolors.alice.validate-test :refer [base keygen-base]]))
(deftest build-and-external-identity
 (is (= "/home/build-placeholder/compute/alice-test/0/identity.pub" (:ssh-private-key-path (ssh/with-machine-key (assoc keygen-base :green/event :build)))))
 (is (= base (ssh/with-machine-key base)))
 (is (= [] (ssh/identity-args base)))
 (is (= ["-i" "/operator/key" "-o" "IdentitiesOnly=yes" "-o" "IdentityAgent=none" "-o" "ForwardAgent=no"] (ssh/identity-args (assoc base :ssh-private-key-path "/operator/key")))))

(deftest direct-access-never-reuses-an-operators-multiplex-session
  (let [args (ssh/direct-args (assoc base :ssh-private-key-path "/scope/identity.pub"
                                   :alice/agent-socket "/scope/agent.sock"))]
    (is (some #{"IdentityAgent=/scope/agent.sock"} args))
    (is (some #{"ControlMaster=no"} args))
    (is (some #{"ControlPersist=no"} args))
    (is (= ["-S" "none"] (take-last 2 args)))))

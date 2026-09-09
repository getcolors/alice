(ns io.github.getcolors.alice.ssh-test
 (:require [clojure.test :refer [deftest is]] [io.github.getcolors.alice.ssh :as ssh]
           [io.github.getcolors.alice.validate-test :refer [base keygen-base]]))
(deftest build-and-external-identity
 (is (= "/home/build-placeholder/.ssh/alice-test" (:ssh-private-key-path (ssh/with-machine-key (assoc keygen-base :green/event :build)))))
 (is (= base (ssh/with-machine-key base)))
 (is (= [] (ssh/identity-args base)))
 (is (= ["-i" "/operator/key" "-o" "IdentitiesOnly=yes"] (ssh/identity-args (assoc base :ssh-private-key-path "/operator/key")))))

(ns io.github.getcolors.alice.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.alice.validate :as validate]))

(def base
  {:profile "alice-test" :workdir ".colors"
   :provider-compute "digitalocean" :provider-dns false
   :provider-backend "local" :compute-prevent-destroy true
   :package "alice"
   :digitalocean-name "alice" :digitalocean-region "ams3"
   :digitalocean-size "s-1vcpu-1gb-35gb-intel"
   :digitalocean-image "ubuntu-24-04-x64"
   :digitalocean-vpc-uuid "00000000-0000-4000-8000-000000000000"
   :digitalocean-ssh-keys "812184"
   :ssh-identity-file "~/.ssh/id_ed25519"
   :transmission-rpc-port 9091
   :transmission-tunnel-local-port 19091
   :transmission-local-directory "~/Downloads/alice"
   :transmission-magnet-links
   ["magnet:?xt=urn:btih:4cdce46e0cda3be676d4d3ae7ba1a1e42a24f2af&dn=fixture"]})

(deftest complete-state-is-valid
  (is (= [] (validate/state-errors base))))

(deftest reports-all-missing-and-invalid-values
  (let [errors (validate/state-errors
                (-> base
                    (dissoc :digitalocean-region :digitalocean-size)
                    (assoc :digitalocean-vpc-uuid "bad"
                           :transmission-rpc-port 70000)))]
    (is (>= (count errors) 4))
    (is (some #(str/includes? % ":digitalocean-region") errors))
    (is (some #(str/includes? % ":digitalocean-vpc-uuid") errors))))

(deftest magnets-and-local-directory-are-validated
  (is (some #(str/includes? % "non-empty YAML list")
            (validate/state-errors (assoc base :transmission-magnet-links []))))
  (is (some #(str/includes? % "40-character BTIH")
            (validate/state-errors
             (assoc base :transmission-magnet-links ["magnet:?dn=missing-hash"]))))
  (is (some #(str/includes? % "unique BTIH")
            (validate/state-errors
             (assoc base :transmission-magnet-links
                    (repeat 2 (first (:transmission-magnet-links base))))))))

(deftest provider-package-and-ports-are-fixed
  (is (some #(str/includes? % "digitalocean")
            (validate/state-errors (assoc base :provider-compute "oci"))))
  (is (some #(str/includes? % ":package")
            (validate/state-errors (assoc base :package "other"))))
  (is (some #(str/includes? % "tunnel-local-port")
            (validate/state-errors
             (assoc base :transmission-tunnel-local-port "19091")))))

(deftest secret-errors-use-colors-variables
  (is (= ["required credential is not set: COLORS_PAR_DO_TOKEN"]
         (vec (validate/secret-errors base))))
  (is (= [] (vec (validate/secret-errors (assoc base :do-token "x"))))))

(deftest destruction-environment-overlay-is-ignored
  (is (true? (:compute-prevent-destroy
              (validate/overlay base
                                {"COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false"})))))

(deftest profile-overlay-is-always-refused
  (is (str/includes? (first (validate/env-errors
                             {"COLORS_PAR_PROFILE" "other"}))
                     "COLORS_PAR_PROFILE")))

(deftest runtime-validation-aggregates-tools-and-token-status
  (let [runner (fn [args _]
                 (cond
                   (= "tofu" (last args)) {:exit 1}
                   (= "curl" (last args)) {:exit 0}
                   (= "https://api.digitalocean.com/v2/account" (last args))
                   {:exit 22}
                   :else {:exit 0}))
        errors (validate/runtime-errors (assoc base :do-token "bad") runner)]
    (is (= 2 (count errors)))
    (is (some #(str/includes? % "tofu") errors))
    (is (some #(str/includes? % "rejected") errors))))

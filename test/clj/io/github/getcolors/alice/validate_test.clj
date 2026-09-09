(ns io.github.getcolors.alice.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.alice.validate :as validate]))

(def base
  {:profile "alice-test" :workdir ".colors"
   :provider-compute "digitalocean" :provider-dns false
   :provider-backend "r2" :r2-bucket "state" :r2-endpoint "https://example.r2.cloudflarestorage.com" :compute-prevent-destroy true
   :digitalocean-region "ams3"
   :digitalocean-size "s-1vcpu-1gb-35gb-intel"
   :digitalocean-image "ubuntu-24-04-x64"
   :digitalocean-vpc-uuid "00000000-0000-4000-8000-000000000000"
   ;; An explicit key id: `base` is opt-out mode, the shape every existing
   ;; deployment has. `keygen-base` below drops it, which is the only switch.
   :digitalocean-ssh-keys "812184"
   :transmission-rpc-port 9091
   :transmission-tunnel-local-port 19091
   :transmission-local-directory "~/Downloads/alice"
   :transmission-magnet-links
   ["magnet:?xt=urn:btih:4cdce46e0cda3be676d4d3ae7ba1a1e42a24f2af&dn=fixture"]})

(def keygen-base
  "Keygen mode: the package owns the machine keypair because desired state
  supplies no key. Presence of `digitalocean-ssh-keys` is the only switch."
  (dissoc base :digitalocean-ssh-keys))

(def discovery-base
  "Both optional keys omitted — the default shape of a deployment."
  (dissoc base :digitalocean-ssh-keys :digitalocean-vpc-uuid))

(deftest complete-state-is-valid (is (= [] (validate/state-errors base))))
(deftest magnets-and-local-directory-are-validated
  ;; An empty list is desired state — no torrent is wanted — so it must not
  ;; raise any error about the key.
  (is (empty? (filter #(str/includes? % ":transmission-magnet-links")
                      (validate/state-errors
                       (assoc base :transmission-magnet-links [])))))
  (is (some #(str/includes? % "must be a YAML list")
            (validate/state-errors
             (assoc base :transmission-magnet-links
                    (first (:transmission-magnet-links base))))))
  (is (some #(str/includes? % "40-character BTIH")
            (validate/state-errors
             (assoc base :transmission-magnet-links ["magnet:?dn=missing-hash"]))))
  (is (some #(str/includes? % "unique BTIH")
            (validate/state-errors
             (assoc base :transmission-magnet-links
                    (vec (repeat 2 (first (:transmission-magnet-links base)))))))))

(deftest profile-and-destruction-overlays
 (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "wrong"})))
 (is (true? (:compute-prevent-destroy (validate/overlay base {"COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false"})))))
(deftest application-ports-remain-validated
 (is (seq (validate/state-errors (assoc base :transmission-rpc-port 0))))
 (is (seq (validate/state-errors (assoc base :transmission-tunnel-local-port 65536)))))
(deftest local-tools-and-library-credential-names
 (is (= (count validate/required-tools) (count (validate/runtime-errors base (fn [& _] {:exit 1})))))
 (is (empty? (validate/runtime-errors base (fn [& _] {:exit 0}))))
 (is (some #(clojure.string/includes? % "COLORS_PAR_DO_TOKEN") (validate/secret-errors base))))

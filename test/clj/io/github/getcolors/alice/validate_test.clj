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

(deftest complete-state-is-valid
  (is (= [] (validate/state-errors base))))

(deftest vpc-discovery-is-the-default-and-a-uuid-opts-out
  ;; A UUID is an opaque account-specific value the region already determines,
  ;; so requiring it would force a manual lookup before a deployment can exist.
  (is (= [] (validate/state-errors discovery-base)))
  (is (true? (validate/vpc-discovery? discovery-base)))
  (is (false? (validate/vpc-discovery? base)))
  (is (true? (validate/vpc-discovery? (assoc base :digitalocean-vpc-uuid "")))))

(deftest a-supplied-vpc-uuid-is-still-shape-checked
  ;; Optional does not mean unvalidated: a malformed UUID is a typo that would
  ;; otherwise surface as an opaque provider error at apply time.
  (is (some #(str/includes? % ":digitalocean-vpc-uuid")
            (validate/state-errors (assoc base :digitalocean-vpc-uuid "not-a-uuid"))))
  (is (= [] (validate/state-errors base))))

(deftest keygen-mode-is-valid-state
  ;; Requiring `digitalocean-ssh-keys` would make keygen mode unreachable.
  (is (= [] (validate/state-errors keygen-base)))
  (is (true? (validate/keygen? keygen-base)))
  (is (false? (validate/keygen? base)))
  ;; A blank value is a placeholder, not a supplied key.
  (is (true? (validate/keygen? (assoc base :digitalocean-ssh-keys "")))))

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
                   (= validate/account-url (last args)) {:exit 0 :out "401"}
                   :else {:exit 0}))
        errors (validate/runtime-errors (assoc base :do-token "bad") runner)]
    (is (= 2 (count errors)))
    (is (some #(str/includes? % "tofu") errors))
    (is (some #(str/includes? % "rejected COLORS_PAR_DO_TOKEN") errors))))

(deftest a-healthy-account-check-reports-nothing
  (is (nil? (validate/api-error {:exit 0 :out "200"}))))

(deftest only-401-and-403-blame-the-credential
  ;; The whole point of the split: an operator told their token was rejected
  ;; will go and rotate it, which is wasted work — and destructive to a
  ;; deployment mid-flight — when the token was never the problem.
  (doseq [status ["401" "403"]]
    (let [err (validate/api-error {:exit 22 :out status})]
      (is (str/includes? err "rejected COLORS_PAR_DO_TOKEN"))
      (is (str/includes? err status)))))

(deftest server-side-failures-say-do-not-rotate
  (doseq [status ["500" "502" "503" "504"]]
    (let [err (validate/api-error {:exit 22 :out status})]
      (is (str/includes? err status))
      (is (str/includes? err "do not rotate"))
      (is (not (str/includes? err "rejected COLORS_PAR_DO_TOKEN"))))))

(deftest rate-limiting-says-the-token-is-valid
  (let [err (validate/api-error {:exit 22 :out "429"})]
    (is (str/includes? err "rate-limited"))
    (is (str/includes? err "token is valid"))))

(deftest a-request-that-never-landed-is-named-as-a-network-failure
  ;; curl writes the literal 000 through %{http_code} when it received no
  ;; response at all, so a zero is not an HTTP status.
  (doseq [result [{:exit 6 :out "000"} {:exit 6 :out ""} {:exit 7 :out nil}]]
    (let [err (validate/api-error result)]
      (is (str/includes? err "could not reach"))
      (is (str/includes? err "not a credential problem")))))

(deftest an-unexpected-status-is-reported-verbatim
  (is (str/includes? (validate/api-error {:exit 22 :out "418"}) "418")))

(deftest the-probe-keeps-the-status-code-and-bounds-the-wait
  ;; Without -w the status cannot be told apart from the exit code, and -f
  ;; would collapse every 4xx and 5xx into one indistinguishable failure.
  (let [seen (atom nil)
        runner (fn [args _] (reset! seen args) {:exit 0 :out "200"})]
    (validate/runtime-errors (assoc base :do-token "t") runner)
    (let [args @seen]
      (is (some #{"%{http_code}"} args))
      (is (some #{"--max-time"} args))
      (is (not (some #{"-fsS"} args))))))

(ns io.github.getcolors.alice.fresh-start-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [io.github.getcolors.alice.compute :as compute]
            [io.github.getcolors.compute-local :as local]
            [io.github.getcolors.alice.fresh-start :as fresh]))

(def opts {:provider-compute "digitalocean" :provider-backend "r2" :profile "demo"
           :r2-bucket "states" :r2-endpoint "https://example.eu.r2.cloudflarestorage.com" :s3-prefix ""})
(def environment {"COLORS_PAR_DO_TOKEN" "SECRET_TOKEN" "COLORS_PAR_R2_ACCESS_KEY_ID" "SECRET_ACCESS"
                  "COLORS_PAR_R2_SECRET_ACCESS_KEY" "SECRET_KEY" "AWS_SESSION_TOKEN" "UNRELATED_SECRET"})
(defn absent [& _] {:exit 254 :err "An error occurred (NoSuchKey) when calling the GetObject operation: missing"})
(defn empty-inventory [path _]
  {:status 200 :body {(if (str/starts-with? path "/v2/droplets") :droplets :ssh_keys) [] :meta {:total 0}}})

(deftest verifies-both-state-keys-and-provider-collections
  (let [calls (atom []) requests (atom [])
        runner (fn [args cwd env timeout]
                 (swap! calls conj args)
                 (is (= "SECRET_KEY" (get env "AWS_SECRET_ACCESS_KEY")))
                 (is (nil? (get env "AWS_SESSION_TOKEN")))
                 (is (not (str/includes? (pr-str args) "SECRET")))
                 (is (= 120000 timeout)) (absent))
        http (fn [path token] (swap! requests conj path) (is (= "SECRET_TOKEN" token)) (empty-inventory path token))]
    (is (= {:status "absent"} (fresh/verify-absence opts environment {:runner runner :http http})))
    (is (= ["demo/alice-node-0.tfstate" "demo/alice-ssh-registration.tfstate"]
           (mapv #(nth % (inc (.indexOf % "--key"))) @calls)))
    (is (= 2 (count @requests)))))

(deftest state-presence-and-read-errors-refuse-before-provider-check
  (doseq [response [{:exit 0 :out "PRIVATE_STATE"}
                    {:exit 254 :err "AccessDenied SECRET_KEY"}
                    {:exit 254 :err "NoSuchBucket"}
                    {:exit -1 :err "NoSuchKey"}]]
    (let [result (fresh/verify-absence opts environment {:runner (fn [& _] response)
                                                        :http (fn [& _] (throw (AssertionError. "provider should not be called")))})]
      (is (= "error" (:status result)))
      (is (str/includes? (get-in result [:error :message]) "states/demo/alice-node-0.tfstate"))
      (is (not (str/includes? (pr-str result) "SECRET_KEY")))
      (is (not (str/includes? (pr-str result) "PRIVATE_STATE"))))))

(deftest pagination-finds-later-consumer-and-never-follows-foreign-url
  (let [paths (atom []) result (fresh/verify-absence opts environment
       {:runner absent :http (fn [path _]
                               (swap! paths conj path)
                               (if (str/ends-with? path "page=1")
                                 {:status 200 :body {:droplets [{:id 1 :name "other"}] :meta {:total 2}
                                                    :links {:pages {:next "https://evil.example/steal"}}}}
                                 {:status 200 :body {:droplets [{:id 2 :name "demo-0"}] :meta {:total 2}}}))})]
    (is (= "error" (:status result)))
    (is (str/includes? (get-in result [:error :message]) "Droplet"))
    (is (str/includes? (get-in result [:error :message]) "'demo-0'"))
    (is (= ["/v2/droplets?per_page=200&page=1" "/v2/droplets?per_page=200&page=2"] @paths))))

(deftest rejects-key-consumer-provider-denial-and-malformed-inventory
  (doseq [response [{:status 401} {:status 403} {:status 200 :body {}}
                    {:status 200 :body {:ssh_keys [] :meta {:total 1}}}
                    {:status 200 :body {:ssh_keys [{:id 1 :name "demo-registration-app-access"}] :meta {:total 1}}}]]
    (let [result (fresh/verify-absence opts environment {:runner absent :http (fn [path token]
                                   (if (str/starts-with? path "/v2/droplets") (empty-inventory path token) response))})]
      (is (= "error" (:status result)))
      (when (= 401 (:status response)) (is (= "digitalocean_token_rejected" (get-in result [:error :auth_reason])))))))

(deftest unsupported-backend-and-cancellation
  (is (= "error" (:status (fresh/verify-absence (assoc opts :provider-backend "gcs") environment {:runner absent :http empty-inventory}))))
  (is (thrown? InterruptedException (fresh/verify-absence opts environment {:runner (fn [& _] (throw (InterruptedException.))) :http empty-inventory}))))

(deftest retained-authority-requires-provider-check-only-for-missing-state
  (is (= "absent" (:status (fresh/verify-state-ownership opts environment
                 {:runner (fn [& _] {:exit 0}) :http (fn [& _] (throw (AssertionError. "inventory unnecessary")))}))))
  (doseq [[missing field resource] [["alice-node-0.tfstate" :droplets "demo-0"]
                                    ["alice-ssh-registration.tfstate" :ssh_keys "demo-registration-app-access"]]]
    (let [calls (atom [])
          result (fresh/verify-state-ownership opts environment
                    {:runner (fn [args & _] (if (some #(str/ends-with? % missing) args) (absent) {:exit 0}))
                     :http (fn [path _] (swap! calls conj path)
                             {:status 200 :body {field [{:id 1 :name resource}] :meta {:total 1}}})})]
      (is (= "error" (:status result)))
      (is (= 1 (count @calls))))))

(deftest s3-uses-provider-credential-chain-without-unrelated-secrets
  (let [seen (atom nil)]
    (is (= "absent" (:status (fresh/verify-absence
                               (assoc opts :provider-backend "s3" :s3-bucket "states" :s3-region "eu-west-1")
                               (assoc environment "AWS_PROFILE" "storage-profile")
                               {:runner (fn [_ _ env _] (reset! seen env) (absent)) :http empty-inventory}))))
    (is (= "storage-profile" (get @seen "AWS_PROFILE")))
    (is (nil? (get @seen "COLORS_PAR_DO_TOKEN")))))

(deftest local-state-is-authoritative-and-errors-are-not-absence
  (doseq [status ["absent" "present" "error"]]
    (let [paths (atom [])]
      (with-redefs [compute/sdk-workdir (fn [_] "/private/runtime")
                    local/presence (fn [path] (swap! paths conj path) {:status status})]
        (is (= (if (= status "absent") "absent" "error")
               (:status (fresh/verify-absence (assoc opts :provider-backend "local") environment
                                             {:http empty-inventory}))))
        (is (every? #(str/starts-with? % "/private/runtime/demo/") @paths))
        (when (= status "absent")
          (is (= ["/private/runtime/demo/0/alice-node-0.tfstate"
                  "/private/runtime/demo/registration-app-access/alice-ssh-registration.tfstate"] @paths)))))))

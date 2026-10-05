(ns io.github.getcolors.alice.fresh-start
  "Read-only evidence required before creating a missing SSH authority."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [io.github.getcolors.alice.compute :as compute]
            [io.github.getcolors.compute :as library]
            [io.github.getcolors.compute-local :as local]
            [io.github.getcolors.compute-runtime :as runtime])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.nio.file Files]))

(defn- refuse! [message]
  (throw (ex-info message {:safe true})))

(defn- request [path token]
  (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 20)) .build)
        req (-> (HttpRequest/newBuilder (URI/create (str "https://api.digitalocean.com" path)))
                (.timeout (Duration/ofSeconds 30)) (.header "Authorization" (str "Bearer " token)) .GET .build)
        response (.send client req (HttpResponse$BodyHandlers/ofString))
        body (.body response)]
    (when (> (count body) 2097152) (refuse! "DigitalOcean inventory response was too large; verify provider resources before retrying."))
    {:status (.statusCode response)
     :body (when (= 200 (.statusCode response)) (json/parse-string body true))}))

(defn- inventory! [http token endpoint field expected]
  (loop [page 1 seen #{} expected-total nil]
    (when (> page 1000) (refuse! "DigitalOcean inventory pagination could not be completed; verify provider resources before retrying."))
    (let [{:keys [status body]} (http (str endpoint "?per_page=200&page=" page) token)]
      (when (= status 401)
        (throw (ex-info "DigitalOcean rejected COLORS_PAR_DO_TOKEN (HTTP 401 Unauthorized). Replace the token in the deployment environment before retrying."
                        {:safe true :auth_reason "digitalocean_token_rejected"})))
      (when-not (= status 200) (refuse! "DigitalOcean inventory could not be read. Check token permissions and connectivity; no replacement SSH identity was created."))
      (let [items (get body field) total (get-in body [:meta :total])
            ids (mapv :id items) next-page (get-in body [:links :pages :next])]
        (when-not (and (map? body) (vector? items) (<= (count items) 200)
                       (integer? total) (<= 0 total) (or (nil? expected-total) (= total expected-total))
                       (every? #(and (map? %) (integer? (:id %)) (pos? (:id %)) (string? (:name %))) items)
                       (= (count ids) (count (set ids))) (not-any? seen ids))
          (refuse! "DigitalOcean inventory was malformed or incomplete; verify provider resources before retrying."))
        (when (some #(= expected (:name %)) items)
          (refuse! (str "The expected DigitalOcean " (if (= field :droplets) "Droplet" "SSH-key registration")
                        " '" expected "' still exists without its authoritative state. Restore the missing ownership records or recover ownership explicitly; no replacement identity was created.")))
        (let [observed (+ (count seen) (count items))]
          (when (or (> observed total) (and (< observed total) (empty? items))
                    (and (= observed total) next-page))
            (refuse! "DigitalOcean inventory changed or pagination was incomplete; retry the read-only verification."))
          (when (< observed total)
            ;; Construct the next request ourselves; never send credentials to a response-provided URL.
            (recur (inc page) (into seen ids) total)))))))

(defn- states-absent! [opts environment runner authority-present?]
  (let [backend (:provider-backend opts)
        files [[compute/node-id compute/state-filename] ["registration-app-access" "alice-ssh-registration.tfstate"]]]
    (when-not (contains? #{"local" "s3" "r2"} backend)
      (refuse! "Automatic fresh-start verification is unsupported for this backend. Restore the existing SSH authority or verify ownership explicitly."))
    (if (= backend "local")
      (into #{} (keep (fn [[node filename]]
        (let [path (str (compute/sdk-workdir opts) "/" (:profile opts) "/" node "/" filename)
              status (:status (local/presence path))]
          (case status "absent" node "present" (if authority-present? nil
            (refuse! (str "Local authoritative state exists at " path ". Restore the SSH authority before recovery.")))
            (refuse! (str "Local authoritative state cannot be read at " path "; verify access before recovery."))))) files))
      (let [directory (Files/createTempDirectory "alice-state-presence-" (local/attrs "rwx------"))
            target (.resolve directory "state")
            env (merge (select-keys environment (cond-> ["PATH" "HOME" "TMPDIR" "AWS_CA_BUNDLE"]
                                                  (= backend "s3") (into ["AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY" "AWS_SESSION_TOKEN"
                                                                           "AWS_PROFILE" "AWS_DEFAULT_PROFILE" "AWS_CONFIG_FILE" "AWS_SHARED_CREDENTIALS_FILE"
                                                                           "AWS_WEB_IDENTITY_TOKEN_FILE" "AWS_ROLE_ARN" "AWS_ROLE_SESSION_NAME"])))
                       {"AWS_PAGER" "" "AWS_CLI_AUTO_PROMPT" "off" "AWS_MAX_ATTEMPTS" "1"})
            prefix (if (= backend "r2") "R2" "S3")
            access (get environment (str "COLORS_PAR_" prefix "_ACCESS_KEY_ID"))
            secret (get environment (str "COLORS_PAR_" prefix "_SECRET_ACCESS_KEY"))]
        (try
          (when (and (= backend "r2") (or (str/blank? access) (str/blank? secret))) (refuse! "Backend credentials are missing; cannot verify absence of authoritative state."))
          (into #{} (keep (fn [[node filename]]
            (let [key (str/join "/" (remove str/blank? [(:s3-prefix opts) (:profile opts) filename]))
                  settings (library/backend-settings opts key)
                  args (cond-> ["aws" "s3api" "get-object" "--bucket" (:bucket settings) "--key" key
                                "--region" (:region settings) "--no-cli-pager"]
                         (get-in settings [:endpoints :s3]) (into ["--endpoint-url" (get-in settings [:endpoints :s3])]))
                  result (runner (conj args (str target)) (str directory)
                                 (cond-> env (= backend "r2") (assoc "AWS_ACCESS_KEY_ID" access "AWS_SECRET_ACCESS_KEY" secret)) 120000)]
              (when (and (= 0 (:exit result)) (not authority-present?))
                (refuse! (str "Authoritative state still exists at " (:bucket settings) "/" key
                              ". Restore the encrypted SSH authority and inspect that state before recovery.")))
              (when-not (or (= 0 (:exit result)) (and (integer? (:exit result)) (pos? (:exit result))
                            (re-find #"(?s)^\s*(?:aws: \[ERROR\]: )?An error occurred \(NoSuchKey\) when calling the GetObject operation(?: \(reached max retries: [0-9]+\))?:" (or (:err result) ""))))
                (refuse! (str "Authoritative state could not be checked at " (:bucket settings) "/" key
                              ". Verify backend credentials, bucket access and connectivity; no replacement identity was created.")))
              (when (not= 0 (:exit result)) node))) files))
          (finally (Files/deleteIfExists target) (Files/deleteIfExists directory)))))))

(defn verify-absence
  ([opts environment] (verify-absence opts environment {}))
  ([opts environment {:keys [runner http authority-present?] :or {runner runtime/run-command http request}}]
   (try
     (when-not (= "digitalocean" (:provider-compute opts)) (refuse! "Fresh-start verification requires the DigitalOcean provider."))
     (let [missing (states-absent! opts environment runner authority-present?)
           token (get environment "COLORS_PAR_DO_TOKEN")]
       (when (and (seq missing) (str/blank? token)) (refuse! "COLORS_PAR_DO_TOKEN is required to verify provider resource absence."))
       (when (contains? missing compute/node-id)
         (inventory! http token "/v2/droplets" :droplets (str (:profile opts) "-0")))
       (when (contains? missing "registration-app-access")
         (inventory! http token "/v2/account/keys" :ssh_keys (str (:profile opts) "-registration-app-access"))))
     {:status "absent"}
     (catch InterruptedException error (throw error))
     (catch Exception error
       {:status "error" :error (cond-> {:code "fresh_start_unverified" :stage "state" :infrastructure_changes "none"
                                       :message (if (:safe (ex-data error)) (.getMessage error)
                                                    "Fresh-start verification failed. Check backend and provider access; no replacement SSH identity was created.")}
                               (:auth_reason (ex-data error)) (assoc :auth_reason (:auth_reason (ex-data error))))}))))

(defn verify-state-ownership
  ([opts environment] (verify-state-ownership opts environment {}))
  ([opts environment deps] (verify-absence opts environment (assoc deps :authority-present? true))))

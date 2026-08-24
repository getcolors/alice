(ns io.github.getcolors.alice.validate
  "Desired-state, credential, tool, and DigitalOcean validation."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [green.process :as process]
            [io.github.getcolors.alice.sync :as sync]
            [io.github.getcolors.once.ssh :as once-ssh]))

(def providers
  {:provider-compute
   ;; Two keys are deliberately absent from :required, and in both cases their
   ;; presence is the only switch. `digitalocean-ssh-keys` chooses between
   ;; opt-out and keygen mode (SSH Keypair Standard §1). `digitalocean-vpc-uuid`
   ;; chooses between a pinned VPC and discovering the region's default one at
   ;; runtime. Requiring either would make the discovering side unreachable.
   {"digitalocean" {:required [:digitalocean-name :digitalocean-region
                                :digitalocean-size :digitalocean-image]
                    :secrets [:do-token]
                    :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}}
   :provider-backend
   {"local" {:required [] :secrets [] :tofu-env {}}
    "s3" {:required [:s3-bucket :s3-region]
          :secrets [:s3-access-key-id :s3-secret-access-key]
          :tofu-env {:s3-access-key-id "AWS_ACCESS_KEY_ID"
                     :s3-secret-access-key "AWS_SECRET_ACCESS_KEY"}}
    "r2" {:required [:r2-bucket :r2-endpoint]
          :secrets [:r2-access-key-id :r2-secret-access-key]
          :tofu-env {:r2-access-key-id "AWS_ACCESS_KEY_ID"
                     :r2-secret-access-key "AWS_SECRET_ACCESS_KEY"}}}})

(def slots [:provider-compute :provider-backend])
(def profile-par (green-cli/par-name :profile))
(def prevent-destroy-par (green-cli/par-name :compute-prevent-destroy))

(defn overlay
  "Apply parameter overlays while silently ignoring the retired environment
  destruction override."
  [opts env]
  (green-cli/read-pars opts (dissoc (into {} env) prevent-destroy-par)))

(defn placeholder? [x]
  (or (nil? x)
      (and (string? x)
           (or (str/blank? x) (= "REPLACE_ME" (str/upper-case x))))))

(defn vpc-discovery?
  "Whether the region's default VPC is discovered at runtime, rather than
  pinned in desired state.

  Discovery is the default because a UUID is an opaque account-specific value
  that says nothing a reader can check, goes stale silently when an account
  changes, and has to be looked up by hand before a deployment can exist at
  all. The region already determines the answer. Supplying an explicit UUID
  remains the escape hatch for a VPC that is not the regional default."
  [opts]
  (placeholder? (:digitalocean-vpc-uuid opts)))

(defn keygen?
  "Whether this deployment owns its machine keypair. Delegates to ONCE, the
  SSH Keypair Standard's reference implementation, so one rule decides it
  everywhere."
  [opts]
  (once-ssh/keygen? opts))

(defn entry [opts slot] (get-in providers [slot (get opts slot)]))
(defn- slot-keys [opts field] (mapcat #(get (entry opts %) field []) slots))
(defn- missing [opts ks] (keep #(when (placeholder? (get opts %)) %) ks))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set. Alice takes profile from colors.yml only; "
          "an environment overlay could redirect OpenTofu state.")]))

(def ^:private profile-re #"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$")
(def ^:private uuid-re #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(def required-keys
  [:profile :workdir :provider-compute :provider-backend
   :compute-prevent-destroy :package
   :transmission-rpc-port :transmission-tunnel-local-port
   :transmission-local-directory :transmission-magnet-links])

(defn- valid-port? [x]
  (and (integer? x) (<= 1 x 65535)))

(defn state-errors [opts]
  (vec
   (concat
    (map #(str % " is required")
         (missing opts (concat required-keys (slot-keys opts :required))))
    (for [slot slots
          :let [provider (get opts slot)]
          :when (not (contains? (get providers slot) provider))]
      (str "unsupported " slot " " (pr-str provider)))
    (when-not (= "digitalocean" (:provider-compute opts))
      [":provider-compute must be digitalocean"])
    (when-not (= "alice" (:package opts))
      [":package must be alice"])
    (when-not (true? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must remain true in desired state"])
    (when-not (or (placeholder? (:profile opts))
                  (re-matches profile-re (str (:profile opts))))
      [":profile must be a safe 1-63 character name"])
    (when (and (not (placeholder? (:digitalocean-vpc-uuid opts)))
               (not (re-matches uuid-re (str (:digitalocean-vpc-uuid opts)))))
      [":digitalocean-vpc-uuid must be a UUID"])
    (for [k [:transmission-rpc-port :transmission-tunnel-local-port]
          :when (not (valid-port? (get opts k)))]
      (str k " must be an integer from 1 to 65535"))
    (when (placeholder? (:transmission-local-directory opts))
      [":transmission-local-directory must be a non-empty path"])
    (when-not (and (vector? (:transmission-magnet-links opts))
                   (seq (:transmission-magnet-links opts)))
      [":transmission-magnet-links must be a non-empty YAML list"])
    (for [[index magnet] (map-indexed vector (:transmission-magnet-links opts))
          :when (or (not (string? magnet))
                    (str/includes? (str magnet) "\n")
                    (nil? (sync/magnet-info-hash magnet)))]
      (str ":transmission-magnet-links[" index
           "] must be a magnet URI with a 40-character BTIH hash"))
    (when (and (sequential? (:transmission-magnet-links opts))
               (not= (count (:transmission-magnet-links opts))
                     (count (distinct (keep sync/magnet-info-hash
                                            (:transmission-magnet-links opts))))))
      [":transmission-magnet-links must have unique BTIH hashes"]))))

(defn secret-errors [opts]
  (map #(str "required credential is not set: " (green-cli/par-name %))
       (distinct (missing opts (slot-keys opts :secrets)))))

(def required-tools ["tofu" "ansible-playbook" "ssh" "curl" "rsync"])

(defn- command-present? [runner command]
  (zero? (:exit (runner ["sh" "-c" "command -v \"$1\" >/dev/null 2>&1" "sh" command] {}))))

(def account-url "https://api.digitalocean.com/v2/account")

(defn api-error
  "Turn one probe of the DigitalOcean account endpoint into an error, or nil.

  The distinction matters more than it looks. `curl -f` exits non-zero for
  every HTTP status at or above 400, so a single message covering all of them
  reports a DigitalOcean outage as a bad credential — and sends the operator
  off to rotate a token that was never the problem. Only 401 and 403 say
  anything about the token. A 5xx is DigitalOcean's own gateway, and the fix
  is to wait and retry, not to touch desired state or credentials.

  A request that never reached the API is the third case: DNS, TLS, a proxy,
  or no route. curl reports that as the literal `000` from `%{http_code}`, so
  a zero status is not an HTTP status at all. That is the operator's network,
  and naming it as such saves the same wasted rotation."
  [{:keys [exit out]}]
  (let [status (some-> out str str/trim (as-> s (re-find #"\d{3}\z" s)) parse-long)]
    (cond
      (or (nil? status) (zero? status))
      (str "could not reach the DigitalOcean API at " account-url
           " (curl exit " exit "): this is a local network, DNS, or TLS "
           "failure, not a credential problem. Check connectivity and retry.")

      (<= 200 status 299) nil

      (#{401 403} status)
      (str "DigitalOcean rejected COLORS_PAR_DO_TOKEN (HTTP " status
           "): the token is missing, expired, revoked, or lacks read access "
           "to the account. Issue a new personal access token and update "
           ".envrc.private.")

      (= 429 status)
      (str "DigitalOcean rate-limited the credential check (HTTP 429). The "
           "token is valid; wait for the limit to reset and retry.")

      (<= 500 status 599)
      (str "the DigitalOcean API returned HTTP " status " for " account-url
           ". That is a failure on DigitalOcean's side, not your credential — "
           "do not rotate COLORS_PAR_DO_TOKEN. Check "
           "https://status.digitalocean.com and retry.")

      :else
      (str "unexpected HTTP " status " from " account-url
           " during the credential check."))))

(defn runtime-errors
  "Check local tools and authenticate the configured DigitalOcean token.

  The runner arity keeps command decisions testable without network access."
  ([opts] (runtime-errors opts process/run))
  ([opts runner]
   (let [present (into {} (map (fn [tool] [tool (command-present? runner tool)]))
                       required-tools)
         tool-errors (for [tool required-tools :when (not (get present tool))]
                       (str "required tool is not on PATH: " tool))
         token (:do-token opts)
         ;; No `-f`: the status code is the diagnosis, so it has to survive
         ;; into stdout instead of collapsing into curl's exit code. Timeouts
         ;; bound a hung gateway — a 504 took fifteen seconds to arrive.
         api-result (when (and (not (placeholder? token)) (get present "curl"))
                      (runner ["curl" "-sS" "-o" "/dev/null"
                               "-w" "%{http_code}"
                               "--connect-timeout" "10" "--max-time" "20"
                               "-H" (str "Authorization: Bearer " token)
                               account-url] {}))]
     (vec (concat tool-errors
                  (when-let [err (some-> api-result api-error)] [err]))))))

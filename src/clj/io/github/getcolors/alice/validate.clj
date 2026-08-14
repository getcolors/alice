(ns io.github.getcolors.alice.validate
  "Desired-state, credential, tool, and DigitalOcean validation."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [green.process :as process]
            [io.github.getcolors.alice.sync :as sync]))

(def providers
  {:provider-compute
   {"digitalocean" {:required [:digitalocean-name :digitalocean-region
                                :digitalocean-size :digitalocean-image
                                :digitalocean-vpc-uuid :digitalocean-ssh-keys]
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
   :compute-prevent-destroy :package :ssh-identity-file
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
    (when-not (and (string? (:ssh-identity-file opts))
                   (not (str/blank? (:ssh-identity-file opts))))
      [":ssh-identity-file must be a private-key path or agent"])
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
         api-result (when (and (not (placeholder? token)) (get present "curl"))
                      (runner ["curl" "-fsS" "-o" "/dev/null"
                               "-H" (str "Authorization: Bearer " token)
                               "https://api.digitalocean.com/v2/account"] {}))]
     (vec (concat tool-errors
                  (when (and api-result (not (zero? (:exit api-result))))
                    ["DigitalOcean API rejected COLORS_PAR_DO_TOKEN"]))))))

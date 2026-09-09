(ns io.github.getcolors.alice.validate
  "Desired-state, credential, tool, and DigitalOcean validation."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [green.process :as process]
            [io.github.getcolors.alice.sync :as sync]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.compute :as library]
            [io.github.getcolors.compute-planning :as planning]
            [io.github.getcolors.compute-deployment-request :as deployment]
            [io.github.getcolors.alice.compute :as compute]))

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

(defn compute-name [opts]
  (get-in (deployment/deployment-requests opts (compute/topology opts) (compute/requirements opts) {:mode "managed" :public_key "ssh-ed25519 PLACEHOLDER managed-by-colors"}) [:shared :name]))
(defn keygen? [opts] (= "managed" (:mode (ssh/mode opts))))
(defn- missing [opts ks] (keep #(when (placeholder? (get opts %)) %) ks))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set. Alice takes profile from colors.yml only; "
          "an environment overlay could redirect OpenTofu state.")]))

(def ^:private name-re #"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$")
(def ^:private profile-re name-re)
(def ^:private uuid-re #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(def required-keys
  ;; No `:package`. It could hold exactly one value, which the defaults already
  ;; supply, so requiring it in desired state asked an operator to transcribe a
  ;; constant (Compute Name Standard §5).
  [:profile :workdir :provider-compute :provider-backend
   :compute-prevent-destroy
   :transmission-rpc-port :transmission-tunnel-local-port
   :transmission-local-directory :transmission-magnet-links])

(defn- valid-port? [x]
  (and (integer? x) (<= 1 x 65535)))

(defn state-errors [opts]
  (vec
   (concat
    (map #(str % " is required")
         (missing opts required-keys))
    (when-not (true? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must remain true in desired state"])
    (when-not (or (placeholder? (:profile opts))
                  (re-matches profile-re (str (:profile opts))))
      [":profile must be a safe 1-63 character name"])
    (for [k [:transmission-rpc-port :transmission-tunnel-local-port]
          :when (not (valid-port? (get opts k)))]
      (str k " must be an integer from 1 to 65535"))
    (when (placeholder? (:transmission-local-directory opts))
      [":transmission-local-directory must be a non-empty path"])
    ;; An empty list is desired state, not a mistake: it says no torrent is
    ;; wanted. `create` still provisions the private UI, and `sync` degenerates
    ;; into provision, prove the UI, copy, destroy with nothing to wait for.
    (when-not (vector? (:transmission-magnet-links opts))
      [":transmission-magnet-links must be a YAML list"])
    ;; Only a list has items to report on. A bare string is already answered by
    ;; the error above, and indexing into it would report one error per
    ;; character.
    (when (sequential? (:transmission-magnet-links opts))
      (for [[index magnet] (map-indexed vector (:transmission-magnet-links opts))
            :when (or (not (string? magnet))
                      (str/includes? (str magnet) "\n")
                      (nil? (sync/magnet-info-hash magnet)))]
        (str ":transmission-magnet-links[" index
             "] must be a magnet URI with a 40-character BTIH hash")))
    (when (and (sequential? (:transmission-magnet-links opts))
               (not= (count (:transmission-magnet-links opts))
                     (count (distinct (keep sync/magnet-info-hash
                                            (:transmission-magnet-links opts))))))
      [":transmission-magnet-links must have unique BTIH hashes"])
    (try (library/backend-plan opts (str (:profile opts) "/compute/shared.tfstate")) [] (catch Exception e [(ex-message e)])))))

(defn secret-errors [opts]
  (let [env (System/getenv)]
    (for [variable (library/credential-requirements opts)
          :let [key (keyword (str/replace (str/lower-case (subs variable 11)) "_" "-"))]
          :when (placeholder? (or (get opts key) (get env variable)))]
      (str "required credential is not set: " variable))))

(def required-tools ["tofu" "ansible-playbook" "ssh" "curl" "rsync"])

(defn- command-present? [runner command]
  (zero? (:exit (runner ["sh" "-c" "command -v \"$1\" >/dev/null 2>&1" "sh" command] {}))))

(defn runtime-errors
  ([opts] (runtime-errors opts process/run))
  ([_ runner]
   (vec (for [tool required-tools :when (not (command-present? runner tool))]
          (str "required tool is not on PATH: " tool)))))

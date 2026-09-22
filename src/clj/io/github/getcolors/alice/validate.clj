(ns io.github.getcolors.alice.validate
  "Desired-state, credential, tool, and DigitalOcean validation."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.alice.process :as process]
            [io.github.getcolors.alice.sync :as sync]
            [io.github.getcolors.compute :as library]
            [io.github.getcolors.alice.compute :as compute]))

(def profile-par (green-cli/par-name :profile))
(def prevent-destroy-par (green-cli/par-name :compute-prevent-destroy))

(defn overlay
  "Apply parameter overlays while silently ignoring the retired environment
  destruction override."
  [opts env]
  (dissoc (green-cli/read-pars opts (dissoc (into {} env) prevent-destroy-par "COLORS_PAR_ALICE_SSH_PASSPHRASE")) :alice-ssh-passphrase))

(defn placeholder? [x]
  (or (nil? x)
      (and (string? x)
           (or (str/blank? x) (= "REPLACE_ME" (str/upper-case x))))))

(defn compute-name [opts]
  (str (:profile opts) "-" compute/node-id))
(defn keygen? [_] true)
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
    (try (compute/plan opts) [] (catch Exception e [(ex-message e)])))))

(defn secret-errors
  ([opts] (secret-errors opts (System/getenv)))
  ([opts env]
   (let [required (cond-> (vec (library/credential-requirements opts))
                    (not= :delete (:green/event opts)) (conj "COLORS_PAR_ALICE_SSH_PASSPHRASE"))]
     (for [variable required
           :let [key (keyword (str/replace (str/lower-case (subs variable 11)) "_" "-"))]
           :when (placeholder? (or (get opts key) (get env variable)))]
       (str "required credential is not set: " variable)))))

(def required-tools ["python3" "tofu" "aws" "ssh-keygen" "ssh-agent" "ssh-add" "ansible-playbook" "ssh" "curl" "rsync"])

(defn- command-present? [runner command]
  (zero? (:exit (runner ["sh" "-c" "command -v \"$1\" >/dev/null 2>&1" "sh" command] {}))))

(defn runtime-errors
  ([opts] (runtime-errors opts process/run))
  ([opts runner]
   (let [required (if (= "local" (:provider-backend opts)) (remove #{"aws"} required-tools) required-tools)
         missing (vec (remove #(command-present? runner %) required))
         python-check (when-not (some #{"python3"} missing)
                        (runner ["python3" "-c" "import fcntl, os, pty, termios; a,b=pty.openpty(); os.close(a); os.close(b)"] {}))]
     (cond-> (mapv #(str "required tool is not on PATH: " %) missing)
       (and python-check (not (zero? (:exit python-check))))
       (conj "python3 must support POSIX fcntl, termios and PTY allocation for encrypted OpenSSH generation")))))

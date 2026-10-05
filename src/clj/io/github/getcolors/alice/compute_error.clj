(ns io.github.getcolors.alice.compute-error
  "Present sanitized library failures with package-specific recovery advice."
  (:require [clojure.string :as str]
            [io.github.getcolors.alice.compute :as compute]
            [io.github.getcolors.compute-ssh :as ssh]))

(defn missing-ssh-authority [opts]
  (let [{:keys [storage object_key]} (ssh/ssh-plan (compute/library-options opts)
                                                (compute/ssh-request opts))
        local? (= "local" (:kind storage))
        location (if local? (:path storage) (str (:bucket storage) "/" object_key))]
    (str/join "\n\n"
      ["Cannot load the existing SSH identity \"app-access\"."
       (str "Missing " (if local? "local file" (str (str/upper-case (:kind storage)) " object")) ":\n  " location)
       "This command requires an existing identity. Restore the encrypted identity from backup if the deployment should still exist."
       "For a fresh deployment, run create or sync. Alice verifies that both backend state records and the DigitalOcean Droplet and SSH-key registration are absent before generating an identity. Deleting local runtime files cannot establish absence or repair missing ownership records."])))

(defn- command-label [command]
  (when (and (vector? command) (seq command)
             (every? #(and (string? %) (re-matches #"[A-Za-z0-9_.-]+" %)) command))
    (str/join " " command)))

(defn- state-context [opts registration?]
  (let [filename (if registration? "alice-ssh-registration.tfstate" compute/state-filename)
        backend (:provider-backend opts)
        state-key (str/join "/" (remove #(or (nil? %) (= "" %))
                                      [(:s3-prefix opts) (:profile opts) filename]))
        bucket (get opts (keyword (str backend "-bucket")))
        location (if (= "local" backend)
                   (str (compute/sdk-workdir opts) "/" (:profile opts) "/"
                        (if registration? "registration-app-access" compute/node-id) "/" filename)
                   (str bucket "/" state-key (when (= "gcs" backend) "/default.tfstate")))]
    (str "Affected " (if registration? "SSH-key registration" "compute node") " state: " location)))

(defn format-error [opts result]
  (let [{:keys [code stage message command executable exit_code stderr credential infrastructure_changes auth_reason]}
        (:error result)
        label (command-label command)
        tofu? (= "tofu" (first command))
        startup? (and tofu? (= stage "init") (#{126 127} exit_code))
        token-rejected? (= "digitalocean_token_rejected" auth_reason)
        inconsistent? (= "state_inconsistent" code)
        registration? (= :registration (:alice/error-context opts))
        title (if (and (= code "command_failed") label)
                (str (cond startup? "Could not start OpenTofu"
                           (and tofu? (= stage "init")) "OpenTofu initialization failed"
                           tofu? "OpenTofu command failed"
                           :else "Compute command failed")
                     ": `" label "`"
                     (when (integer? exit_code) (str " exited with code " exit_code)) ".")
                (or (not-empty message) "Compute operation failed; no diagnostic details were returned."))
        asdf? (and startup? (string? stderr)
                   (str/includes? stderr "No version is set for command tofu"))
        verb (if (contains? #{:create :sync :delete :describe} (:green/event opts))
               (name (:green/event opts)) "create")]
    (str/join "\n\n"
      (remove nil?
        [(when token-rejected? "DigitalOcean rejected COLORS_PAR_DO_TOKEN (HTTP 401 Unauthorized).")
         title
         (when inconsistent? (state-context opts registration?))
         (when (and inconsistent? registration?)
           "The SSH-key registration step stopped. Verify the existing DigitalOcean SSH-key registration before recovering this state; retain the encrypted app-access SSH authority and do not generate a replacement identity.")
         (when (and label (not= code "command_failed"))
           (str "Command: `" label "`"
                (when (integer? exit_code) (str " (exit code " exit_code ")"))))
         (when (and (or token-rejected? (not label)) (not-empty stage)) (str "Stage: " stage))
         (when (and (string? credential) (re-matches #"COLORS_PAR_[A-Z0-9_]+" credential))
           (str "Required credential: " credential))
         (when (not-empty executable) (str "Executable: " executable))
         (when (and (string? stderr) (not (str/blank? stderr))) (str/trim stderr))
         (when token-rejected?
           (str "Replace COLORS_PAR_DO_TOKEN in the deployment’s ignored .envrc.private with a valid DigitalOcean API token. "
                "After checking any infrastructure-change warning below, retry:\n  direnv exec . ./green " verb))
         (when asdf? (str "Run with the deployment toolchain:\n  direnv exec . ./green " verb))
         (case infrastructure_changes
           "none" "No infrastructure changes were made by this operation."
           "possible" "Infrastructure changes may have occurred. Inspect node state before retrying."
           nil)]))))

(defn failed-result [opts result]
  (assoc (dissoc opts :alice/error-context) :green/exit 1 :green/err (format-error opts result)))

(ns io.github.getcolors.alice.compute-error
  "Present sanitized library failures with package-specific recovery advice."
  (:require [clojure.string :as str]))

(defn- command-label [command]
  (when (and (vector? command) (seq command)
             (every? #(and (string? %) (re-matches #"[A-Za-z0-9_.-]+" %)) command))
    (str/join " " command)))

(defn format-error [opts result]
  (let [{:keys [code stage message command executable exit_code stderr credential infrastructure_changes]}
        (:error result)
        label (command-label command)
        tofu? (= "tofu" (first command))
        startup? (and tofu? (= stage "init") (#{126 127} exit_code))
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
        [title
         (when (and label (not= code "command_failed"))
           (str "Command: `" label "`"
                (when (integer? exit_code) (str " (exit code " exit_code ")"))))
         (when (and (not label) (not-empty stage)) (str "Stage: " stage))
         (when (and (string? credential) (re-matches #"COLORS_PAR_[A-Z0-9_]+" credential))
           (str "Required credential: " credential))
         (when (not-empty executable) (str "Executable: " executable))
         (when (and (string? stderr) (not (str/blank? stderr))) (str/trim stderr))
         (when asdf? (str "Run with the deployment toolchain:\n  direnv exec . ./green " verb))
         (case infrastructure_changes
           "none" "No infrastructure changes were made by this operation."
           "possible" "Infrastructure changes may have occurred. Inspect node state before retrying."
           nil)]))))

(defn failed-result [opts result]
  (assoc opts :green/exit 1 :green/err (format-error opts result)))

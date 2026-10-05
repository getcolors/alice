(ns io.github.getcolors.alice.access
  "Named encrypted SSH authority and one isolated workflow agent."
  (:require [green.scope :as scope]
            [clojure.string :as str]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.compute-node :as library]
            [io.github.getcolors.alice.compute :as compute]
            [io.github.getcolors.alice.fresh-start :as fresh-start]
            [io.github.getcolors.alice.compute-error :as compute-error]))

(def ^:dynamic *register!* nil)
(defn scoped [f]
  (scope/with-scope (fn [register!] (binding [*register!* register!] (f)))))
(def request compute/ssh-request)
(defn planning? [opts] (or (= :build (:green/event opts)) (:green/dry-run opts)))
(defn- cleanup-runtime! [directory]
  (with-open [walk (java.nio.file.Files/walk directory (make-array java.nio.file.FileVisitOption 0))]
    (let [paths (vec (iterator-seq (.iterator walk)))
          recovery? (some (fn [path]
                            (let [relative (.relativize directory path)
                                  filename (str (.getFileName path))]
                              (and (not-any? #(= ".terraform" (str %)) (iterator-seq (.iterator relative)))
                                   (or (str/ends-with? filename ".tfstate")
                                       (str/ends-with? filename ".tfstate.backup"))))) paths)]
      (if recovery?
        (binding [*out* *err*]
          (println (str "Preserved OpenTofu recovery state in " directory
                        ". Back up this directory and recover remote state before retrying; Alice will not reuse it as ownership evidence.")))
        (doseq [path (reverse paths)] (java.nio.file.Files/deleteIfExists path))))))

(defn runtime-workdir [opts]
  (if (or (planning? opts) (= "local" (:provider-backend opts)) (:alice/runtime-workdir opts)
          (not (#{:create :sync :delete :describe :tunnel} (:green/event opts))))
    opts
    (do
      (when-not *register!* (throw (ex-info "Remote operations require an owned runtime scope." {})))
      (let [directory (java.nio.file.Files/createTempDirectory
                        "alice-runtime-"
                        (into-array java.nio.file.attribute.FileAttribute
                          [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                             (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------"))]))]
        ;; Register first: process finalizers run before resources, and the later
        ;; agent resource finalizer runs before this directory under LIFO cleanup.
        (*register!* :resource #(cleanup-runtime! directory))
        (assoc opts :workdir (str directory) :alice/runtime-workdir true)))))

(defn resource-step [opts]
  (let [environment (System/getenv)
        inspected (if (planning? opts) compute/placeholder-resource
                      (ssh/ssh-resource! (compute/library-options opts) (request opts) "inspect" environment))
        fresh? (and (#{:create :sync} (:green/event opts))
                    (= "ssh_authority_missing" (get-in inspected [:error :code])))
        result (if fresh?
                 (let [verified (fresh-start/verify-absence opts environment)]
                   (if (= "absent" (:status verified))
                     (ssh/ssh-resource! (compute/library-options opts)
                                        (assoc (request opts) :verified_absent true) "create" environment)
                     verified))
                 (if (and (#{:create :sync} (:green/event opts))
                          (not (planning? opts)) (= "ready" (:status inspected)))
                   (let [verified (fresh-start/verify-state-ownership opts environment)]
                     (if (= "absent" (:status verified)) inspected verified))
                   inspected))]
    (if (= "ready" (:status result))
      (assoc opts :alice/ssh-resource result :green/exit 0)
      (if (= "ssh_authority_missing" (get-in result [:error :code]))
        (assoc opts :green/exit 1 :green/err (compute-error/missing-ssh-authority opts))
        (compute-error/failed-result opts result)))))
(defn registration-step [opts]
  (let [result (if (planning? opts)
                 (library/build-registration! (compute/library-options opts) (compute/registration-request opts))
                 (library/compute-registration! (compute/library-options opts) (compute/registration-request opts) "create"))]
    (if (#{"ready" "built"} (:status result))
      (cond-> (assoc opts :green/exit 0) (= "ready" (:status result)) (assoc :alice/ssh-registration result))
      (compute-error/failed-result (assoc opts :alice/error-context :registration) result))))
(defn agent-step [opts]
  (if (planning? opts)
    (assoc opts :ssh-private-key-path (compute/placeholder-key opts) :alice/agent-socket "/home/build-placeholder/agent.sock" :green/exit 0)
    (let [agent (ssh/start-agent! [{:opts (compute/library-options opts) :request (request opts)
                                   :resource (:alice/ssh-resource opts)}]
                                 (System/getenv) *register!*)]
      (assoc opts :alice/agent-socket (:socket agent)
             :ssh-private-key-path (get (:identities agent) (:reference (:alice/ssh-resource opts)))
             :green/exit 0))))
(defn join-step [opts]
  (reduce (fn [result branch]
            (merge result (select-keys branch [:colors-compute/node :ip :user :alice/ssh-registration
                                               :alice/agent-socket :ssh-private-key-path])))
          (dissoc opts :green/branches) (:green/branches opts)))
(defn registration-delete-step [opts]
  (let [result (library/compute-registration! (assoc (compute/library-options opts) :compute-prevent-destroy false)
                                             (compute/registration-request opts) "delete")]
    (if (= "destroyed" (:status result)) (assoc opts :green/exit 0)
        (compute-error/failed-result (assoc opts :alice/error-context :registration) result))))

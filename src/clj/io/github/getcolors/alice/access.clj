(ns io.github.getcolors.alice.access
  "Named encrypted SSH authority and one isolated workflow agent."
  (:require [green.scope :as scope]
            [clojure.java.io :as io]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.compute-node :as library]
            [io.github.getcolors.alice.compute :as compute]
            [io.github.getcolors.alice.compute-error :as compute-error]))

(def ^:dynamic *register!* nil)
(defn scoped [f]
  (scope/with-scope (fn [register!] (binding [*register!* register!] (f)))))
(def request compute/ssh-request)
(defn planning? [opts] (or (= :build (:green/event opts)) (:green/dry-run opts)))
(defn- existing-consumer? [opts]
  ;; Presence is evidence only: never parse generated templates as source.
  (java.nio.file.Files/exists
   (.toPath (io/file (compute/sdk-workdir opts) (:profile opts) compute/node-id "compute.tf.json"))
   (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])))
(defn resource-step [opts]
  (let [result (if (planning? opts) compute/placeholder-resource
                  (ssh/ssh-resource! (compute/library-options opts) (request opts)
                                     (if (or (#{:describe :tunnel :delete} (:green/event opts)) (existing-consumer? opts)) "inspect" "create")
                                     (System/getenv)))]
    (if (= "ready" (:status result))
      (assoc opts :alice/ssh-resource result :green/exit 0)
      (compute-error/failed-result opts result))))
(defn registration-step [opts]
  (let [result (if (planning? opts)
                 (library/build-registration! (compute/library-options opts) (compute/registration-request opts))
                 (library/compute-registration! (compute/library-options opts) (compute/registration-request opts) "create"))]
    (if (#{"ready" "built"} (:status result))
      (cond-> (assoc opts :green/exit 0) (= "ready" (:status result)) (assoc :alice/ssh-registration result))
      (compute-error/failed-result opts result))))
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
        (compute-error/failed-result opts result))))

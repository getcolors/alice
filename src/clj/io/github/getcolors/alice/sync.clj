(ns io.github.getcolors.alice.sync
  "Ephemeral Transmission download, local rsync, and tunnel lifecycle."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [green.process :as process]
            [io.github.getcolors.alice.utils :as utils]))

(def remote-download-directory "/var/lib/transmission-daemon/downloads")
(def poll-interval-ms 30000)

(defn magnet-info-hash [magnet]
  (some->> (re-find #"(?i)(?:^|[?&])xt=urn:btih:([0-9a-f]{40})(?:&|$)"
                    (str magnet))
           second
           str/lower-case))

(defn local-directory [opts]
  (let [configured (str (:transmission-local-directory opts))
        expanded (if (or (= configured "~") (str/starts-with? configured "~/"))
                   (str (System/getProperty "user.home") (subs configured 1))
                   configured)
        file (io/file expanded)]
    (str (if (.isAbsolute file)
           file
           (io/file (-> (:green/state-file opts) io/file .getAbsoluteFile .getParentFile)
                    expanded)))))

(defn rsync-command
  ([opts destination] (rsync-command opts destination false))
  ([opts destination checksum?]
   (cond-> ["rsync" "-a" "--partial"]
     checksum? (conj "--checksum")
     true (into ["-e" (str "ssh -o IgnoreUnknown=UseKeychain -F "
                            (process/posix-quote (utils/ssh-config-path)))
                 (str (utils/host-alias opts) ":" remote-download-directory "/")
                 (str destination "/")]))))

(defn- ssh-command [opts & remote-args]
  (into ["ssh" "-o" "IgnoreUnknown=UseKeychain"
         "-F" (utils/ssh-config-path)
         "--" (utils/host-alias opts)]
        remote-args))

(defn- fail! [label {:keys [exit out err]}]
  (throw (ex-info (str label " failed: "
                       (or (not-empty err) (not-empty out) "(no output)"))
                  {:green/exit (max 1 (or exit 1))})))

(defn- run-checked! [label args]
  (let [result (process/run args)]
    (when-not (zero? (:exit result)) (fail! label result))
    result))

(defn- temp-path [prefix suffix]
  (let [file (java.io.File/createTempFile prefix suffix)]
    (.deleteOnExit file)
    (.getAbsolutePath file)))

(defn tunnel-start-command [opts control-path]
  (let [local-port (:transmission-tunnel-local-port opts)
        remote-port (:transmission-rpc-port opts)]
    ["ssh" "-o" "IgnoreUnknown=UseKeychain"
     "-F" (utils/ssh-config-path)
     "-o" "BatchMode=yes"
     "-o" "ExitOnForwardFailure=yes"
     "-o" "ControlMaster=yes"
     "-o" (str "ControlPath=" control-path)
     "-o" "ControlPersist=no"
     "-fN" "-L" (format "127.0.0.1:%d:127.0.0.1:%d" local-port remote-port)
     "--" (utils/host-alias opts)]))

(defn tunnel-stop-command [opts control-path]
  ["ssh" "-o" "IgnoreUnknown=UseKeychain"
   "-F" (utils/ssh-config-path)
   "-S" control-path "-O" "exit" "--" (utils/host-alias opts)])

(defn- session-id [headers]
  (some->> (re-find #"(?im)^x-transmission-session-id:\s*(\S+)\s*$" headers)
           second))

(defn rpc-call
  "Call Transmission RPC through the active local tunnel. `session` is an atom
  so the 409 session handshake is shared by subsequent calls."
  ([opts session method arguments]
   (rpc-call opts session method arguments process/run))
  ([opts session method arguments runner]
   (let [headers (temp-path "alice-rpc-" ".headers")
         body (temp-path "alice-rpc-" ".json")
         url (format "http://127.0.0.1:%d/transmission/rpc"
                     (:transmission-tunnel-local-port opts))
         request (json/generate-string {:method method :arguments arguments})]
     (try
       (loop [attempt 0]
         (let [args (cond-> ["curl" "-sS" "-D" headers "-o" body
                             "-w" "%{http_code}" "-X" "POST"
                             "-H" "Content-Type: application/json"]
                      @session (into ["-H" (str "X-Transmission-Session-Id: " @session)])
                      true (into ["--data-binary" request url]))
               result (runner args {})
               status (some-> (:out result) str/trim parse-long)]
           (when-not (zero? (:exit result)) (fail! "Transmission RPC" result))
           (cond
             (and (= 409 status) (< attempt 2))
             (if-let [sid (session-id (slurp headers))]
               (do (reset! session sid) (recur (inc attempt)))
               (throw (ex-info "Transmission RPC omitted its session ID"
                               {:green/exit 1})))

             (= 200 status)
             (let [response (json/parse-string (slurp body) true)]
               (if (= "success" (:result response))
                 (:arguments response)
                 (throw (ex-info (str "Transmission RPC failed: " (:result response))
                                 {:green/exit 1}))))

             :else
             (throw (ex-info (str "Transmission RPC returned HTTP " status)
                             {:green/exit 1})))))
       (finally
         (.delete (io/file headers))
         (.delete (io/file body)))))))

(defn completed-hashes [torrents desired]
  (into #{}
        (keep (fn [{:keys [hashString percentDone]}]
                (let [hash (some-> hashString str/lower-case)]
                  (when (and (contains? desired hash)
                             (number? percentDone)
                             (>= (double percentDone) 1.0))
                    hash))))
        torrents))

(defn- rsync!
  ([opts destination] (rsync! opts destination false))
  ([opts destination checksum?]
   (let [result (process/run-inherit (rsync-command opts destination checksum?))]
     (when-not (zero? (:exit result)) (fail! "rsync" result)))))

(defn- stop-transmission! [opts]
  (run-checked! "stopping Transmission"
                (ssh-command opts "systemctl" "stop" "transmission-daemon")))

(defn- add-magnets! [opts session]
  (let [magnets (:transmission-magnet-links opts)]
    (when (empty? magnets)
      (println "No desired magnet links; nothing to download.")
      (flush))
    (doseq [magnet magnets]
      (rpc-call opts session "torrent-add" {:filename magnet}))))

(defn- await-downloads! [opts session destination]
  (let [desired (set (map magnet-info-hash (:transmission-magnet-links opts)))
        total (count desired)]
    (loop [last-completed 0]
      (let [response (rpc-call opts session "torrent-get"
                               {:fields ["hashString" "percentDone" "name"]})
            completed (completed-hashes (:torrents response) desired)
            n (count completed)]
        (when (not= n last-completed)
          (println (format "Downloaded %d/%d desired torrents" n total))
          (flush))
        (when (> n last-completed)
          (rsync! opts destination))
        (if (= n total)
          completed
          (do (Thread/sleep poll-interval-ms)
              (recur n)))))))

(defn sync-step [opts]
  (let [destination (local-directory opts)
        control-path (temp-path "alice-sync-" ".sock")
        _ (.delete (io/file control-path))
        stopped? (atom false)
        stop-tunnel! (fn []
                       (when (compare-and-set! stopped? false true)
                         (process/run (tunnel-stop-command opts control-path))))
        shutdown-hook (Thread. ^Runnable stop-tunnel!)]
    (.mkdirs (io/file destination))
    (run-checked! "SSH tunnel" (tunnel-start-command opts control-path))
    (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
    (try
      (println (format "Transmission UI: http://127.0.0.1:%d/transmission/web/"
                       (:transmission-tunnel-local-port opts)))
      (flush)
      (let [session (atom nil)]
        ;; The first RPC call also proves that the tunnel and private RPC UI
        ;; work. With no desired magnet that call is the `torrent-get` below,
        ;; which still runs before the empty desired set is satisfied.
        (add-magnets! opts session)
        (await-downloads! opts session destination)
        (stop-transmission! opts)
        ;; A checksum pass after quiescing Transmission proves the local copy,
        ;; even when an incomplete file had previously been preallocated.
        (rsync! opts destination true)
        (assoc opts :green/exit 0 :alice/synced-directory destination))
      (finally
        (try (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
             (catch IllegalStateException _))
        (stop-tunnel!)))))

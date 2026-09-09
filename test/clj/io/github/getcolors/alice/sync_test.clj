(ns io.github.getcolors.alice.sync-test
  (:require [green.process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.alice.sync :as sync]))

(def magnet
  "magnet:?xt=urn:btih:4cdce46e0cda3be676d4d3ae7ba1a1e42a24f2af&dn=kali")

(deftest extracts-case-insensitive-btih-identity
  (is (= "4cdce46e0cda3be676d4d3ae7ba1a1e42a24f2af"
         (sync/magnet-info-hash magnet)))
  (is (nil? (sync/magnet-info-hash "magnet:?dn=no-hash"))))

(deftest completion-is-limited-to-desired-finished-torrents
  (let [desired #{"4cdce46e0cda3be676d4d3ae7ba1a1e42a24f2af"}]
    (is (= desired
           (sync/completed-hashes
            [{:hashString "4CDCE46E0CDA3BE676D4D3AE7BA1A1E42A24F2AF"
              :percentDone 1.0}
             {:hashString "306d9b5251c2209a723675fbdd60a87072dba2bb"
              :percentDone 1.0}]
            desired)))
    (is (empty? (sync/completed-hashes
                 [{:hashString (first desired) :percentDone 0.99}]
                 desired)))))

(deftest rsync-copies-download-contents-directly-without-delete
  (let [command (sync/rsync-command
                 {:profile "alice-digitalocean"}
                 "/home/ubuntu/Downloads/alice")]
    (is (some #{"alice-digitalocean:/var/lib/transmission-daemon/downloads/"}
              command))
    (is (= "/home/ubuntu/Downloads/alice/" (last command)))
    (is (some #{"--partial"} command))
    (is (not-any? #{"--delete"} command))))

(deftest tunnel-command-is-private-and-fails-closed
  (let [command (sync/tunnel-start-command
                 {:profile "alice-digitalocean"
                  :transmission-rpc-port 9091
                  :transmission-tunnel-local-port 19091}
                 "/tmp/alice.sock")]
    (is (some #{"ExitOnForwardFailure=yes"} command))
    (is (some #{"127.0.0.1:19091:127.0.0.1:9091"} command))
    (is (str/includes? (str/join " " command) "ControlPath=/tmp/alice.sock"))))

(deftest final-copy-is-checksummed-after-daemon-stop
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory "alice-sync-copy-" (make-array java.nio.file.attribute.FileAttribute 0)))
        calls (atom [])
        opts {:profile "demo" :transmission-local-directory (str directory) :transmission-magnet-links []
              :transmission-rpc-port 9091 :transmission-tunnel-local-port 19091}]
    (try
      (with-redefs [sync/rpc-call (fn [& _] {:torrents []})
                    green.process/run (fn [argv & _] (swap! calls conj argv) {:exit 0})
                    green.process/run-inherit (fn [argv] (swap! calls conj argv) {:exit 0})]
        (is (= 0 (:green/exit (sync/sync-step opts)))))
      (let [commands @calls stop (first (keep-indexed #(when (some #{"transmission-daemon"} %2) %1) commands))
            copy (first (keep-indexed #(when (= "rsync" (first %2)) %1) commands))]
        (is (< stop copy))
        (is (some #{"--checksum"} (nth commands copy))))
      (with-redefs [sync/rpc-call (fn [& _] {:torrents []})
                    green.process/run (fn [& _] {:exit 0})
                    green.process/run-inherit (fn [_] {:exit 1 :err "checksum failed"})]
        (is (thrown? Exception (sync/sync-step opts))))
      (finally (.delete directory)))))

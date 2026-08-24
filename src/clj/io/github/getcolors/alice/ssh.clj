(ns io.github.getcolors.alice.ssh
  "The deployment's machine keypair, per the workspace SSH Keypair Standard.

  The behaviour is ONCE's (`io.github.getcolors.once.ssh`): keygen mode when
  desired state carries no `digitalocean-ssh-keys`, an ed25519 key named after
  the profile in `~/.ssh`, the create matrix, the DigitalOcean REST preflight,
  and a cleanup that runs only after a successful destroy. Reusing it rather
  than reimplementing means one standard has one implementation, and a fix
  upstream reaches this package when the pin moves.

  Two things are added here.

  The first is a build-time placeholder. ONCE derives the key paths from
  `$HOME` and does not commit rendered output; alice does commit goldens, so on
  `:build` the rendered paths must not name the operator's home directory or
  the goldens would differ per workstation. Real events use the real paths.

  The second is `sync`. In every other package `sync` is an auxiliary verb and
  the standard's §3 rightly forbids it from touching key material. Alice's
  `sync` is the whole lifecycle in one event — it creates a Droplet, downloads
  through it, and destroys it — so the key has to be born and buried inside it
  or the invariant `key present ⇔ deployment exists` cannot hold. The DAG
  resolves this by relabelling the phases (`workflow/as-event`), so what runs
  here is still a create and still a delete; `sync` itself never has key
  lifecycle of its own."
  (:require [clojure.java.io :as io]
            [io.github.getcolors.once.ssh :as once-ssh]))

(def build-placeholder-dir
  "The `~/.ssh` stand-in rendered on `:build`. Fixed, so a build is
  byte-identical on every workstation and the committed goldens mean
  something."
  "/home/build-placeholder/.ssh")

(defn keygen?
  "Whether this deployment owns its machine keypair. Delegates to ONCE, the
  standard's reference implementation, so one rule decides it everywhere."
  [opts]
  (once-ssh/keygen? opts))

(defn rendered-only?
  "Whether this event only renders: a `build`, or any `--dry-run`. The standard
  holds both to the same rule — neither may read, create, or require anything
  under `~/.ssh`, and both must render byte-identically whether or not the
  keypair exists. A dry-run is a create that touches nothing, so testing the
  event alone would let it reach the real key path."
  [opts]
  (or (= :build (:green/event opts))
      (boolean (:green/dry-run opts))))

(defn with-machine-key
  "Fill the template values keygen mode owns. Opt-out opts pass through
  untouched, byte-for-byte as before the standard."
  [opts]
  (if-not (keygen? opts)
    opts
    (let [build? (rendered-only? opts)
          opts (once-ssh/with-machine-key opts (not build?))]
      (if-not build?
        opts
        (let [profile (or (:profile opts) "alice")
              prv (str build-placeholder-dir "/" profile)
              pub (str prv ".pub")]
          (assoc opts
                 :ssh-private-key-path prv
                 :ssh-public-key-path pub
                 :digitalocean-ssh-keys pub))))))

(defn ensure-key!
  "The standard's create matrix, generation, and permission enforcement, on a
  real create."
  [opts state-fn]
  (once-ssh/ensure-key! opts state-fn))

(defn preflight!
  "Refuse a real create when the DigitalOcean account holds a key named after
  the profile that this deployment's state does not own."
  [opts]
  (once-ssh/preflight! opts))

(defn cleanup-step
  "Remove the generated keypair, strictly after the compute destroy succeeded.

  Reaching this step means the destroy returned successfully, which is what
  makes removal safe. A failed or interrupted run leaves the key in place,
  correctly: it is still the only credential to a Droplet that may still be
  alive."
  [opts]
  (once-ssh/cleanup-step opts))

(defn private-key-path [opts]
  (str (.getAbsolutePath (io/file (once-ssh/private-key-path opts)))))

(defn public-key-path [opts]
  (str (.getAbsolutePath (io/file (once-ssh/public-key-path opts)))))

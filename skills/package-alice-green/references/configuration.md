# Configuration

`colors.yml` is a flat, non-secret YAML map. The reference deployment is
`alice-digitalocean/colors.yml`. Validation reports every desired-state problem
together.

## Credential

| Purpose | Environment variable |
|---|---|
| DigitalOcean API | `COLORS_PAR_DO_TOKEN` |
| R2 backend | `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` |
| S3 backend | `COLORS_PAR_S3_ACCESS_KEY_ID`, `COLORS_PAR_S3_SECRET_ACCESS_KEY` |

Never export `COLORS_PAR_PROFILE`.

The package refuses to run against a `~/.ssh/config` that already declares
`Host <profile>` outside its own markers, or whose first option stands above the
first `Host` line. The first may be the operator's only record of how to reach
something; the second would be captured into this deployment's stanza by the
top-of-file insert and silently narrowed from a global setting to one host.
Both name the file and line and leave the decision to a human.

## Desired state

Required keys select the unique profile/work directory, DigitalOcean compute,
and local/S3/R2 state backend. DigitalOcean needs a Droplet name, region, size,
image, and an existing VPC UUID.

Alice also accepts:

- `digitalocean-ssh-keys` — an existing SSH key ID or fingerprint. Omit it and
  the package owns the machine keypair instead: it generates
  `~/.ssh/<profile>`(`.pub`), registers a DigitalOcean key named after the
  profile, and removes both once the Droplet is destroyed. Presence is the only
  switch — in opt-out mode no key material is generated, validated, or deleted,
  and the managed block carries no `IdentityFile`;
- `transmission-rpc-port` — remote loopback RPC port, normally 9091;
- `transmission-tunnel-local-port` — default local forwarding port, normally 19091;
- `transmission-local-directory` — local destination that directly receives the
  contents of Transmission's download directory;
- `transmission-magnet-links` — non-empty list of quoted public magnet URIs,
  each with a unique 40-character BTIH hash.

Keep `package: alice` and `compute-prevent-destroy: true`.
`COLORS_PAR_COMPUTE_PREVENT_DESTROY` is ignored. The explicit `delete` event
owns manual destruction authorization; `sync` owns only its successful final
cleanup.

## Lifecycle

Create generates the machine keypair before any provider call, refuses if a key
exists that state does not account for or if DigitalOcean already holds a key
named after the profile that this deployment does not own, provisions the
Droplet, writes `Host <profile>` into `~/.ssh/config`, installs Transmission, forces RPC onto loopback, and verifies the web UI through
a real SSH tunnel. RPC password authentication is disabled because loopback plus
SSH is the sole access boundary. Ubuntu 24.04's packaged AppArmor 4 profile
cannot notify systemd even in complain mode, so the playbook disables that
profile before starting the service. Delete removes the managed SSH block before
destroying the Droplet, and the local keypair only after the destroy has
succeeded — a failed delete leaves the key, because it is still the only way in. With a local backend, retain `.colors/` until deletion
completes; otherwise the state needed to address the Droplet is lost.

`sync` creates or resumes the deployment, opens and prints the private UI
tunnel, adds missing desired magnets, and rsyncs whenever another desired
torrent completes. Once all are complete it stops Transmission and performs a
checksummed final rsync before deleting the Droplet. Rsync copies the remote
directory contents directly into the local destination, supports partial
transfers, and never uses `--delete`. Any failure or interruption retains the
Droplet and state for a retry.

Generated output is reproducible and may contain the Droplet's public address,
but never credentials. Do not edit or commit it.

## Recovery

A repeated `create` converges the same state. If Transmission is inactive, SSH
to the profile and inspect `systemctl status transmission-daemon` and
`journalctl -u transmission-daemon`. If the local alias is stale, rerun create;
do not edit `.colors/`. If local state is lost, recover or import the Droplet
into `digitalocean_droplet.alice` before attempting deletion.

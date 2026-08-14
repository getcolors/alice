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

## Desired state

Required keys select the unique profile/work directory, DigitalOcean compute,
and local/S3/R2 state backend. DigitalOcean needs a Droplet name, region, size,
image, existing VPC UUID, and one existing SSH key ID or fingerprint.

Alice also accepts:

- `ssh-identity-file` — local private-key path written into the managed SSH
  block, or `agent` to use the current SSH agent without forcing a key file;
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

Create provisions the Droplet, writes `Host <profile>` into `~/.ssh/config`,
installs Transmission, forces RPC onto loopback, and verifies the web UI through
a real SSH tunnel. RPC password authentication is disabled because loopback plus
SSH is the sole access boundary. Ubuntu 24.04's packaged AppArmor 4 profile
cannot notify systemd even in complain mode, so the playbook disables that
profile before starting the service. Delete removes the managed SSH block before
destroying the Droplet. With a local backend, retain `.colors/` until deletion
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

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

- `ssh-identity-file` — local private-key path written into the managed SSH block;
- `transmission-rpc-port` — remote loopback RPC port, normally 9091;
- `transmission-tunnel-local-port` — default local forwarding port, normally 19091.

Keep `package: alice` and `compute-prevent-destroy: true`.

## Lifecycle

Create provisions the Droplet, writes `Host <profile>` into `~/.ssh/config`,
installs Transmission, forces RPC onto loopback, and verifies the web UI through
a real SSH tunnel. RPC password authentication is disabled because loopback plus
SSH is the sole access boundary. Ubuntu 24.04's packaged AppArmor 4 profile
cannot notify systemd even in complain mode, so the playbook disables that
profile before starting the service. Delete removes the managed SSH block before
destroying the Droplet. With a local backend, retain `.colors/` until deletion
completes; otherwise the state needed to address the Droplet is lost.

Generated output is reproducible and may contain the Droplet's public address,
but never credentials. Do not edit or commit it.

## Recovery

A repeated `create` converges the same state. If Transmission is inactive, SSH
to the profile and inspect `systemctl status transmission-daemon` and
`journalctl -u transmission-daemon`. If the local alias is stale, rerun create;
do not edit `.colors/`. If local state is lost, recover or import the Droplet
into `digitalocean_droplet.alice` before attempting deletion.

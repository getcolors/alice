# Configuration

`colors.yml` is a flat, non-secret YAML map. The reference deployment is
`alice-digitalocean/colors.yml`. Validation reports every desired-state problem
together.

## Credential

| Purpose | Environment variable |
|---|---|
| DigitalOcean API | `COLORS_PAR_DO_TOKEN` |
| R2 backend | `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` |
| S3 backend | Ambient AWS credential chain |

Never export `COLORS_PAR_PROFILE`.

The package refuses to run against a `~/.ssh/config` that already declares
`Host <profile>` outside its own markers, or whose first option stands above the
first `Host` line. The first may be the operator's only record of how to reach
something; the second would be captured into this deployment's stanza by the
top-of-file insert and silently narrowed from a global setting to one host.
Both name the file and line and leave the decision to a human.

## Desired state

Required keys select the unique profile/work directory, DigitalOcean compute,
and S3/R2 state backend. DigitalOcean needs a region, size, and image —
not a Droplet name, which defaults to the profile.

Alice also accepts:

- `digitalocean-name` — the Droplet's name. Omit it and the Droplet is named
  after the profile, which is already what keys OpenTofu state, names the
  machine keypair and its DigitalOcean registration, and serves as the
  `~/.ssh/config` alias; the machine's own label should not be the one place
  that disagrees. Supply one only for an account whose naming policy a profile
  cannot satisfy, or an existing Droplet being adopted, and it is checked for
  shape (`standards/compute-name.md`). Changing it renames the Droplet at
  DigitalOcean but never the running guest's hostname, which cloud-init set at
  creation — a name change takes effect on the next create;
- `digitalocean-vpc-uuid` — an existing VPC UUID. Omit it and the region's
  default VPC is discovered at runtime through a `digitalocean_vpc` data
  source, with the apply asserting that the discovered VPC really is the
  account default. Supply one only to target a VPC that is not the regional
  default; a supplied value is checked for UUID shape and the library reads its
  observed CIDR and verifies its region without owning the VPC;
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
- `transmission-magnet-links` — list of quoted public magnet URIs, each with a
  unique 40-character BTIH hash. The key is required but the list may be empty:
  `[]` is desired state meaning no torrent is wanted.

Keep `compute-prevent-destroy: true`. There is no `package` key: it could hold
exactly one value, so desired state no longer carries it.
`COLORS_PAR_COMPUTE_PREVENT_DESTROY` is ignored. The explicit `delete` event
owns manual destruction authorization; `sync` owns only its successful final
cleanup.

## Lifecycle

The pinned colors-compute library verifies state ownership, checks provider
registration and records key intent before generation. It resolves the default
or explicit VPC, provisions one node, writes `Host <profile>` into `~/.ssh/config`, installs Transmission, forces RPC onto loopback, and verifies the web UI through
a real SSH tunnel. RPC password authentication is disabled because loopback plus
SSH is the sole access boundary. Ubuntu 24.04's packaged AppArmor 4 profile
cannot notify systemd even in complain mode, so the playbook disables that
profile before starting the service. Delete removes the managed SSH block before
destroying the Droplet, and the local keypair only after the destroy has
succeeded. A failed delete retains the key. Compute state and its ownership
journal are remote under `<profile>/compute/`; generated build files contain
only non-secret backend settings.

`sync` creates or resumes the deployment, opens and prints the private UI
tunnel, adds missing desired magnets, and rsyncs whenever another desired
torrent completes. Once all are complete it stops Transmission and performs a
checksummed final rsync before deleting the Droplet. With an empty
`transmission-magnet-links` the desired set is satisfied immediately, so `sync`
provisions, proves the UI over the tunnel, copies the download directory as it
stands, and then deletes — use `create` and `tunnel` instead when the intent is
a UI to keep open. Rsync copies the remote
directory contents directly into the local destination, supports partial
transfers, and never uses `--delete`. Any failure or interruption retains the
Droplet and state for a retry.

Generated output is reproducible and may contain the Droplet's public address,
but never credentials. Do not edit or commit it.

## Recovery

A repeated `create` converges the same state. If Transmission is inactive, SSH
to the profile and inspect `systemctl status transmission-daemon` and
`journalctl -u transmission-daemon`. If the local alias is stale, rerun create;
do not edit `.colors/`. Unreadable or foreign state stops the lifecycle.
Legacy `<profile>/alice-infrastructure.tfstate` must be migrated explicitly
before running this version against an existing deployment.

The provider firewall allows SSH22 and Transmission peer TCP/UDP51413, with
outbound traffic allowed. RPC9091 remains loopback-only.

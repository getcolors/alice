# Configuration

`colors.yml` is a flat, non-secret YAML map. The reference deployment is
`alice-digitalocean/colors.yml`. Never export `COLORS_PAR_PROFILE` or embed
credentials in desired state. `COLORS_PAR_COMPUTE_PREVENT_DESTROY` is ignored.

## Credentials and storage

| Purpose | Credentials |
|---|---|
| DigitalOcean compute | `COLORS_PAR_DO_TOKEN` |
| R2 state and keys | `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` |
| AWS S3 state and keys | Ambient AWS credential chain |
| Separate key storage for GCS state | Ambient AWS chain, or both `COLORS_PAR_SSH_S3_ACCESS_KEY_ID` and `COLORS_PAR_SSH_S3_SECRET_ACCESS_KEY` |

S3/R2 state uses `<s3-prefix>/<profile>/alice-node-0.tfstate`, with an empty
prefix by default. Key objects use `<s3-prefix>/<profile>/0/ssh-key` and
`ssh-key.pub`. S3-compatible remote state uses its bucket for those key objects.

The local backend owns the keypair in local OpenTofu state and needs no S3
settings or credentials. Access files are refreshed from that state.

With a GCS backend, configure nonsecret `ssh-s3-bucket` and
`ssh-s3-region`. Set `ssh-s3-endpoint` for a compatible service such as R2.
An endpoint does not change which credential variables apply: separate key
storage uses `COLORS_PAR_SSH_S3_*`.

Remote key objects and the generated keypair are OpenTofu resources in the
node's state. State and saved plans contain private key material and require
private, encrypted storage. Local key files are disposable copies; existing
local keys are never uploaded or adopted.

## Desired state

Required keys select a stable profile/workdir, DigitalOcean compute, and a
state backend. DigitalOcean requires region, size, and image. Alice requests
node `0`, named `<profile>-0`, and provides filename `alice-node-0.tfstate`.

OpenTofu runs in `<workdir>/<profile>/0/`, with its normal `.terraform/` folder.
For a local backend, the state file is also in that directory. Build writes
Terraform templates without provisioning. Templates and initialization files
are retained after node destruction.

Alice accepts:

- `digitalocean-vpc-uuid`: existing VPC. If omitted, the region's default VPC is
  discovered and verified. Alice never owns that VPC.
- `alice-ssh-sources`: explicit SSH ingress CIDRs; an empty list is invalid.
- `transmission-rpc-port`: remote loopback RPC port, normally 9091.
- `transmission-tunnel-local-port`: local forwarding port, normally 19091.
- `transmission-local-directory`: destination receiving download-directory contents.
- `transmission-magnet-links`: required list of quoted magnet URIs, each with a
  unique 40-character BTIH hash. `[]` means no torrent is wanted.

External SSH-key references are no longer supported. The node owns its
keypair and provider registration. Keep `compute-prevent-destroy: true` in
desired state. Explicit `delete` authorizes destruction; `sync` authorizes it
only after every desired torrent completes and the final checksummed copy succeeds.

## Lifecycle

Create provisions the compute node and key resources through one locked
OpenTofu state. It retrieves the authoritative keypair from local state or remote
objects into the SDK node directory, overwriting the access copy, then writes the package-owned
`Host <profile>` block in `~/.ssh/config`. That block and Ansible use the SDK
key path, with `IdentitiesOnly` and `IdentityAgent none`.

The package refuses to overwrite a foreign SSH alias or to capture global
options above the first `Host` block. Ansible installs Transmission, binds RPC
to `127.0.0.1`, and disables the Ubuntu 24.04 Transmission AppArmor profile
that prevents systemd notification. Create verifies the web UI over an SSH
local forward. Port 9091 is never exposed publicly.

`sync` provisions or resumes the node, opens the private UI tunnel, adds desired
magnets, and incrementally copies completed downloads. It stops Transmission
for the final checksummed rsync, then removes the SSH alias and destroys the
node. Node destruction precedes removal of its key resources and local copies.
Any failed download, copy, or state read stops cleanup and retains recovery material.

An empty magnet list completes immediately after one copy. Use `create` plus
`tunnel` for a UI that should stay open. Rsync never uses `--delete`.

## Recovery and migration

Repeated create uses the same node directory and state key. State read failures
never mean absence. Foreign remote keys, missing ownership, and provider changes
require explicit recovery; local keys cannot repair missing state ownership.

Old `<profile>/alice-infrastructure.tfstate` and `<profile>/compute/` states
belong to earlier architectures. Retain their resources and files until an
explicit state transfer or verified old deployment deletion is complete. A new
filename does not migrate or adopt existing infrastructure.

If Transmission is inactive, inspect its service status and journal over SSH.
Use `./green tunnel 19091` and open
`http://127.0.0.1:19091/transmission/web/` for private access. Do not manually edit
generated files.

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
| Encrypted app-access identity | `COLORS_PAR_ALICE_SSH_PASSPHRASE` |

SSH uses the named encrypted `app-access` resource, independent of node `0`.
Set `COLORS_PAR_ALICE_SSH_PASSPHRASE` at runtime. The resource inherits the
workflow backend; local authority lives under the SDK workdir and profile,
and R2 authority uses the configured bucket with a profile-containing path.
No plaintext private key enters OpenTofu state or application files.

Create makes the encrypted authority durable, then runs provider registration
and compute alongside a dedicated scoped SSH agent. Application steps join both
branches. SSH, Ansible, acceptance, describe, tunnel, and sync explicitly select
the public identity and the temporary agent socket; agent forwarding is disabled.
The operator's agent is unchanged. Scope cleanup stops the owned agent on success
or failure. The package still owns and validates its local SSH config block.

Delete removes the alias, destroys compute, and deletes the separate provider
registration. It retains the encrypted SSH resource. SSH resource destruction
and passphrase rotation are separate explicit library operations. Changing the
passphrase environment variable does not rotate the key. Missing authority or
an incorrect secret fails closed. This is a greenfield API; existing deployments
are not adopted or migrated automatically.

## Desired state

Required keys select a stable profile/workdir, DigitalOcean compute, and a
state backend. DigitalOcean requires region, size, and image. Alice requests
node `0`, named `<profile>-0`, and provides filename `alice-node-0.tfstate`.

OpenTofu runs in `<workdir>/<profile>/0/`, with its normal `.terraform/` folder.
For a local backend, the state file is also in that directory. Build writes
Terraform previews under `<workdir>/build/<profile>/` without provisioning. Templates and initialization files
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

Keep `compute-prevent-destroy: true` in desired state. Explicit `delete`
authorizes compute destruction; `sync` authorizes it after the final successful
checksummed copy. Both retain the encrypted SSH resource.

## Lifecycle

The package refuses to overwrite a foreign SSH alias or to capture global
options above the first `Host` block. Ansible installs Transmission, binds RPC
to `127.0.0.1`, and disables the Ubuntu 24.04 Transmission AppArmor profile
that prevents systemd notification. Create verifies the web UI over an SSH
local forward. Port 9091 is never exposed publicly.

`sync` provisions or resumes the node, opens the private UI tunnel, adds desired
magnets, and incrementally copies completed downloads. It stops Transmission
for the final checksummed rsync, then removes the SSH alias and destroys the
node and its provider key registration. The encrypted SSH resource remains.
Any failed download, copy, or state read stops cleanup and retains recovery material.

An empty magnet list completes immediately after one copy. Use `create` plus
`tunnel` for a UI that should stay open. Rsync never uses `--delete`.

## Recovery

State read failures never mean absence. Missing encrypted authority requires
explicit recovery. A retry preserves the same public identity.

If Transmission is inactive, inspect its service status and journal over SSH.
Use `./green tunnel 19091` and open
`http://127.0.0.1:19091/transmission/web/` for private access. Do not manually edit
generated files.

## Compute diagnostics

The package displays the compute library's safe failure details, including the
stage, command, resolved executable, exit code, and sanitized stderr when
available. An asdf shim with no selected OpenTofu version is a toolchain failure,
not an ownership mismatch. Run the command through the deployment environment,
for example `direnv exec . ./green sync`.

A failure before apply reports that this operation made no infrastructure
changes. Failure during or after apply reports that changes may have occurred;
inspect node state before retrying. This does not imply that resources from an
earlier run are absent. Diagnostic handling never retries apply automatically.

Credential-free `build` renders all preview stages under `<workdir>/build/<profile>/`.
Real lifecycle operations keep `<workdir>/<profile>/`. This isolates placeholder
identities and generated previews from live templates, state, and encrypted SSH
authority, so build remains safe after a deployment exists. Preview files are
replaced on subsequent builds; they are never used to provision the deployment.

For working-tree development, the launcher directly honors `ALICE_LIB_ROOT`,
`GREEN_LIB_ROOT`, and `COLORS_COMPUTE_LIB_ROOT` (the latter points to the library's
`green/` directory). No wrapper script is required.

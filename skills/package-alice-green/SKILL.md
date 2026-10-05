---
name: package-alice-green
description: Provision one DigitalOcean Droplet, configure Transmission, manage a local SSH alias, and expose its UI only through an SSH tunnel.
license: MIT
---

# Alice Transmission server

Read [references/configuration.md](references/configuration.md) before changing
state or running a lifecycle command.

## Safety

- Keep credentials out of `colors.yml`; use ignored `COLORS_PAR_*` exports.
- Never set `COLORS_PAR_PROFILE` and never edit generated `.colors/` files.
- Use `build` and `create --dry-run` before a real lifecycle operation.
- Keep `compute-prevent-destroy: true`. The environment override is ignored;
  `delete` authorizes manual destruction and `sync` authorizes only its
  post-rsync cleanup.
- Transmission binds its RPC UI to loopback and relies on SSH as its access
  boundary. Do not expose port 9091 publicly; use the tunnel command.

## Commands

```sh
./green validate
./green build
./green create --dry-run
./green create
./green sync
./green describe
./green tunnel 19091
./green delete
```

While `tunnel` runs, open
`http://127.0.0.1:19091/transmission/web/`. A successful create already performs
this tunnel check before returning. `sync` creates its own tunnel, prints that
URL, adds desired magnets, incrementally rsyncs completed downloads directly
into the configured local directory, verifies a final checksummed copy, and
then destroys the Droplet. Failures retain it for a retry.

Compute provisioning uses one `colors-compute` node (`0`) with its own state
(`alice-node-0.tfstate`). Alice supplies the firewall policy and orders the
application workflow. OpenTofu runs in `<runtime-workdir>/<profile>/0/`; the
local backend retains templates and initialization files after deletion.

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
passphrase environment variable does not rotate the key. An incorrect secret or
failed authority read fails closed. Create and sync may generate a missing identity
only after read-only checks confirm both backend state records and the matching
DigitalOcean Droplet and SSH-key registration are absent. Existing records or
provider resources require recovery; local files cannot establish ownership. This is a greenfield API; existing deployments
are not adopted or migrated automatically.

Repeated delete resumes only local generated-file cleanup when a successful
library inspection confirms strictly empty state. Missing or unreadable state
still stops deletion. Local-backend templates and initialization files are retained.

Credential-free `build` renders all preview stages under `<workdir>/build/<profile>/`.
Remote-backed lifecycle commands use a private temporary workdir for each invocation,
including describe and tunnel. Owned processes and the SSH agent stop before its
removal. If OpenTofu leaves an emergency state snapshot or backup outside
`.terraform/`, Alice preserves the temporary directory and prints its recovery
path. Back it up and recover remote state before retrying; it is never reused
automatically or treated as authoritative ownership. Remote state and encrypted
authority determine ownership; stale local
templates and caches do not. Previously retained workdirs remain untouched as
recovery material. Local-backed commands retain `<workdir>/<profile>/` because
it contains authoritative state and SSH authority. Preview files are replaced on
subsequent builds and never used to provision the deployment.

For working-tree development, the launcher directly honors `ALICE_LIB_ROOT`,
`GREEN_LIB_ROOT`, and `COLORS_COMPUTE_LIB_ROOT` (the latter points to the library's
`green/` directory). No wrapper script is required.

Fresh-start checks match the configured Droplet and SSH-key registration names.
If ownership records were deleted after resources were renamed, or if a detached
reserved IP remains, these name checks cannot establish ownership. Recover the
missing records and verify those resources separately; fresh-start checks are
not automatic adoption or a complete account inventory.

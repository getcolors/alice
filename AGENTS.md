# CLAUDE.md

## What this is

`alice` is a Green-only Package Skill for one Transmission server on an existing
DigitalOcean VPC. It provisions one Droplet, manages a local SSH alias, installs
Transmission, binds the RPC UI to loopback, and verifies it through an SSH
tunnel. The consumer is `../alice-digitalocean`.

## Commands

```sh
bb test
bb golden
./scripts/launcher.sh
./green validate
./green build
./green create --dry-run
./green sync
./green describe
./green tunnel 19091
```

Never run real create/delete without explicit authorization. Never edit or read
`.colors/`, and never read `.envrc.private`.

## Compute boundary and identity

Alice supplies one stable node identifier, `0`, and state filename
`alice-node-0.tfstate` to colors-compute. The node is named `<profile>-0`.
Alice owns application ordering: provisioning, SSH config, Ansible, downloads,
checksummed copy, and teardown. The compute library owns the node; separate SSH and registration APIs own key
resources; it does not expand topology or orchestrate a deployment.

OpenTofu runs in `<SDK workdir>/<profile>/0/` and uses its default `.terraform/`
directory. Build renders separate previews under `<workdir>/build/<profile>/`. Templates,
initialization files, and the node directory are never deleted by cleanup.
The S3 state key is `<s3-prefix>/<profile>/alice-node-0.tfstate`; local state
lives at `<SDK workdir>/<profile>/0/alice-node-0.tfstate`. An empty S3 prefix
omits its separator.

There is no lifecycle journal, coordinator, retirement marker, or shared state.
Native OpenTofu state locking protects infrastructure operations; the SDK/caller
serializes operations sharing a working directory. Preserve old monolithic and
shared/node states until their ownership has been explicitly transferred or the
old resources have been verified destroyed. New filenames do not adopt resources.

## Existing VPC

`digitalocean-vpc-uuid` is optional. Presence selects an explicit existing VPC;
absence discovers the region's default VPC. The library validates the reference,
reads its CIDR, and asserts that default discovery really returned the account
default. Alice never owns the VPC. Nothing discovered is written into desired state.

## SSH resources and agent sessions

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

## Architecture and safety

Create is `start -> SSH resource -> [registration -> compute | agent] -> join ->
ansible-local -> ansible-remote -> acceptance`. Delete is `start -> SSH inspection
-> load-infrastructure -> ansible-local -> infrastructure -> registration-delete
-> generated-cleanup`. Remove the SSH alias before destroying the node; remove
the provider registration after destruction and retain the encrypted SSH resource.

`sync` hosts a create and, only after all desired torrents finish and the final
checksummed rsync succeeds, a delete. Its teardown stages remain relabelled with
`workflow/as-event :delete` so package event guards retain their meaning.

Build and dry-run are credential-free. Validate checks desired state, tools,
and credential presence. Credentials use only the documented environment inputs
and never render. `COLORS_PAR_PROFILE` is always refused. Keep
`compute-prevent-destroy: true` in desired state; its environment override is
ignored. Explicit delete authorizes manual destruction; sync authorizes only
successful post-copy teardown.

`transmission-magnet-links: []` means no torrent is wanted. It is valid desired
state, but sync then copies once and destroys the node. Use create plus tunnel
for a UI intended to stay open. Magnet query components are URL-decoded once;
encoded `xt=urn%3Abtih%3A...` is accepted without inventing query parameters from
escaped delimiters.

Transmission RPC binds to `127.0.0.1`, with SSH as the authentication boundary.
Create must finish the real tunneled UI acceptance check. Sync opens its own
forward, adds magnets, incrementally copies downloads, stops the daemon for a
final checksummed copy, then tears down. Rsync never uses `--delete`.

Ubuntu 24.04's AppArmor 4 Transmission profile prevents systemd notification.
The playbook disables that broken profile and verifies it is unloaded. Do not
remove that step without proving the packaged profile can notify systemd.

## Repeated deletion

Only a successful library inspection of readable state with both empty resources
and empty outputs confirms destruction. Repeated Alice delete then skips former
hosts, removed keys, and infrastructure operations and resumes generated-file
cleanup. Missing or unreadable state still refuses. Cleanup retains compute
templates, initialization files, state, and unrelated files.

## Pins and installed launchers

Manage `alice-sha` only with `bb pin` after a clean pushed commit. Never invent
or hand-edit it. The deployment's root launcher and installed skill payload are
copies and must remain byte-identical after updates. Use `ALICE_LIB_ROOT=../alice`
for working-tree development.

## Verification

`bb test`, `bb golden`, and `./scripts/launcher.sh` are all required. Inspect
golden output rather than accepting it blindly. The package manual, README,
skill instructions, and configuration reference must agree.

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable so one Analytics
property can separate repositories, and the self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every page because `getcolors.github.io/<repo>/`
paths already encode the repository. Never add one tag without the other.

## Git

Work on the current branch. Do not commit or push unless explicitly authorized.

Credential-free `build` renders all preview stages under `<workdir>/build/<profile>/`.
Real lifecycle operations keep `<workdir>/<profile>/`. This isolates placeholder
identities and generated previews from live templates, state, and encrypted SSH
authority, so build remains safe after a deployment exists. Preview files are
replaced on subsequent builds; they are never used to provision the deployment.

For working-tree development, the launcher directly honors `ALICE_LIB_ROOT`,
`GREEN_LIB_ROOT`, and `COLORS_COMPUTE_LIB_ROOT` (the latter points to the library's
`green/` directory). No wrapper script is required.

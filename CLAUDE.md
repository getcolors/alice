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

## Two optional keys, one idiom

`digitalocean-ssh-keys` and `digitalocean-vpc-uuid` are both optional, and in
both cases **presence is the only switch** — there is no mode flag. Omit them
and the package supplies the answer itself; supply them and it uses what it was
given. The compute library validates an explicit VPC UUID through a data lookup; it never owns that VPC.

`digitalocean-vpc-uuid` absent means the region's default VPC is read through a
`digitalocean_vpc` data source, and the Droplet's `lifecycle` asserts
`data.digitalocean_vpc.default.default` so a region that answered with some
other VPC fails the apply instead of placing the Droplet on an unexpected
network silently. A UUID is an opaque account-specific value that says nothing
a reader can check, goes stale when an account changes, and has to be looked up
by hand before a deployment can exist; the region already determines it. The
explicit key remains the escape hatch for a VPC that is not the regional
default. A supplied UUID is still shape-checked — optional is not unvalidated.
Nothing discovered is ever written back into desired state.

## The machine keypair and the SSH alias

The package implements the workspace standards `standards/ssh-keypair.md` and
`standards/ssh-config.md`. Absence of `digitalocean-ssh-keys` in desired state
is keygen mode and the only switch: the package generates
`~/.ssh/<profile>`(`.pub`), declares a `digitalocean_ssh_key` named after the
profile in its own state, writes a `~/.ssh/config` block aliased `<profile>`
with `IdentityFile`/`IdentitiesOnly`/`IdentityAgent none`, and removes all of
it on the way out.
Supplying an explicit key id is opt-out: no key material is generated,
validated, or deleted, no account key resource is created, and the block carries
no `IdentityFile`, because the operator has their own arrangements for finding
their key and guessing is worse than silence.

Key lifecycle belongs to the pinned colors-compute library; the `~/.ssh/config` play
is deliberately alice's own copy, because that file is shared with every other
host the operator reaches and an unrelated upstream change must not rewrite it
at pin-bump time. There is no rotation verb: Droplet key sets are ForceNew, so
rotation is `delete` then `create`.

The managed block is not what the remote stage connects with. `ansible.cfg`
passes `-F /dev/null`, so the run cannot depend on a shared file the local
stage is rewriting in the same create, and that also discards the block's
`IdentityFile`. In keygen mode the rendered `inventory.json` therefore names
`ansible_ssh_private_key_file` itself — a path, never key material, through the normalized library result. Remove it and a create succeeds only on a workstation whose agent
already holds the generated key, and fails `Permission denied (publickey)`
everywhere else. The keygen inventory also carries
`ansible_ssh_common_args: -o IdentitiesOnly=yes -o IdentityAgent=none`: the
generated key is passphrase-less and ephemeral, so the agent contributes
nothing, while stale agent copies of superseded machine keys — banked by
`AddKeysToAgent`, outliving the deleted file — would otherwise be offered
first and exhaust `MaxAuthTries` as `Too many authentication failures`.
`IdentityAgent none` in both the inventory and the managed block keeps the
agent out of every path the package owns; opt-out mode says nothing, because
the operator's own key arrangements may include the agent.

The compute library journals key intent before generation and refuses to adopt
unowned key files or provider registrations. An unreadable backend never means
absence. Managed key cleanup happens only after confirmed node, shared resource
and registration destruction. Interrupted cleanup can be retried through the
owned lifecycle; do not delete keys based on a failed state read.

`sync` is the one place alice departs from the letter of the keypair standard,
which bars a `sync` from touching key material. That clause is written for
packages where `sync` is auxiliary and leaves the machine alone; alice's `sync`
*is* the lifecycle. The DAG resolves it rather than deviating: the teardown
steps run relabelled as `:delete` through `workflow/as-event`, so what executes
is still a create and still a delete, and `sync` has no key lifecycle of its
own. Do not "simplify" that relabelling away — the library dispatches lifecycle operations by `:green/event`.

The marker is mid-migration. Alice used to write `# BEGIN alice <alias> ...`;
the standard's marker carries the alias alone. `ansible-local/main.yml` removes
the superseded block before writing the new one, and `ssh-config.clj`
recognises the old marker as its own so the ownership check does not refuse the
migration meant to clean it up. Retire the removal task and the superseded
markers together, one pin cycle from now, or not at all.

## Architecture and safety

Create is `start -> infrastructure -> ansible-local -> ansible-remote ->
acceptance`. Delete is `start -> load-infrastructure -> ansible-local -> infrastructure
-> generated-cleanup`: the managed `~/.ssh/config` block goes before the destroy
and the keypair strictly after it. Those two orders disagree deliberately — a
stale block is harmless, a key removed ahead of its Droplet locks you out of a
machine that still exists — and `standards/ssh-config.md` §4 forbids tidying
them into agreement. Build and dry-run are credential-free. Validate reports desired-state, local tool and library credential-presence errors. Provider account checks belong to the library lifecycle.

Credentials use only `COLORS_PAR_*` and never render. `COLORS_PAR_PROFILE` is
always refused. Keep `compute-prevent-destroy: true` in desired state;
`COLORS_PAR_COMPUTE_PREVENT_DESTROY` is ignored. Explicit `delete` authorizes
manual destruction. `sync` authorizes destruction only after every desired
torrent is complete and the final checksummed rsync succeeds.

The Droplet is named after the profile (`standards/compute-name.md`).
`digitalocean-name` is an optional override, resolved once by
the library deployment request so each node renders one name —
the same "presence is the only switch" shape as the VPC and the keypair. There
is no `package` key in desired state: it could hold exactly one value.

`transmission-magnet-links` may be empty. `[]` is desired state — no torrent is
wanted — not a validation failure, so `create` still provisions the private UI.
It does mean `sync` satisfies its desired set on the first `torrent-get` and
tears the Droplet down after one copy; `create` plus `tunnel` is the verb pair
for a UI meant to stay open.

The package requests one existing-network node through colors-compute. Provider
recipes, credentials, default/explicit VPC selection, SSH keys and remote S3/R2
state belong to that dependency; there is no package compute registry or
template. New provider capabilities arrive with a library version bump. The UI is
not a public service: Transmission RPC binds 127.0.0.1, RPC password auth is
disabled because SSH is the only access boundary, and acceptance opens a
short-lived SSH local forward before curling the web UI. `sync` keeps its own
forward open, prints the UI URL, adds desired magnets, incrementally rsyncs
completed downloads directly into the configured local directory, stops the
daemon for a final checksummed rsync, and only then deletes the deployment.
Failures retain the Droplet and state. Remote compute objects live under `<profile>/compute/`. Legacy monolithic
`<profile>/alice-infrastructure.tfstate` requires explicit migration.

Ubuntu 24.04's AppArmor 4 Transmission profile returns EACCES for systemd's
disconnected notify socket even in complain mode, causing every service start to
time out. The playbook disables that broken profile and verifies it is unloaded.
Do not remove that task without proving the packaged profile can notify systemd.

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

### Repeated deletion after compute retirement

A repeated `delete` with validated retired compute ownership resumes only the
local generated-file cleanup. It does not require removed SSH keys or contact
the former hosts, DNS, registry, or other application cloud resources. Failed
ownership inspection still stops deletion. Local cleanup preserves unrelated
files and is safe to repeat.

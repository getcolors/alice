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

## The machine keypair and the SSH alias

The package implements the workspace standards `standards/ssh-keypair.md` and
`standards/ssh-config.md`. Absence of `digitalocean-ssh-keys` in desired state
is keygen mode and the only switch: the package generates
`~/.ssh/<profile>`(`.pub`), declares a `digitalocean_ssh_key` named after the
profile in its own state, writes a `~/.ssh/config` block aliased `<profile>`
with `IdentityFile`/`IdentitiesOnly`, and removes all of it on the way out.
Supplying an explicit key id is opt-out: no key material is generated,
validated, or deleted, no account key resource is created, and the block carries
no `IdentityFile`, because the operator has their own arrangements for finding
their key and guessing is worse than silence.

Key behaviour is ONCE's (`io.github.getcolors.once.ssh`), reused rather than
reimplemented so one standard has one implementation; the `~/.ssh/config` play
is deliberately alice's own copy, because that file is shared with every other
host the operator reaches and an unrelated upstream change must not rewrite it
at pin-bump time. There is no rotation verb: Droplet key sets are ForceNew, so
rotation is `delete` then `create`.

An existing `~/.ssh/<profile>` with no readable compute state is an error, never
an overwrite — it may be the only credential to a Droplet that is still alive.
Because `sync` destroys the Droplet on success, a run interrupted between the
destroy and the key removal leaves that state behind and the next `sync` will
refuse until the key is removed by hand. That is the standard working, not a
bug: verify at DigitalOcean that no Droplet for the profile survives, then
remove `~/.ssh/<profile>` and `~/.ssh/<profile>.pub`.

`sync` is the one place alice departs from the letter of the keypair standard,
which bars a `sync` from touching key material. That clause is written for
packages where `sync` is auxiliary and leaves the machine alone; alice's `sync`
*is* the lifecycle. The DAG resolves it rather than deviating: the teardown
steps run relabelled as `:delete` through `workflow/as-event`, so what executes
is still a create and still a delete, and `sync` has no key lifecycle of its
own. Do not "simplify" that relabelling away — `cleanup-step` gates on
`:green/event`, and it would silently start leaving keys behind.

The marker is mid-migration. Alice used to write `# BEGIN alice <alias> ...`;
the standard's marker carries the alias alone. `ansible-local/main.yml` removes
the superseded block before writing the new one, and `ssh-config.clj`
recognises the old marker as its own so the ownership check does not refuse the
migration meant to clean it up. Retire the removal task and the superseded
markers together, one pin cycle from now, or not at all.

## Architecture and safety

Create is `start -> infrastructure -> ansible-local -> ansible-remote ->
acceptance`. Delete is `start -> ansible-local -> infrastructure -> ssh-cleanup
-> generated-cleanup`: the managed `~/.ssh/config` block goes before the destroy
and the keypair strictly after it. Those two orders disagree deliberately — a
stale block is harmless, a key removed ahead of its Droplet locks you out of a
machine that still exists — and `standards/ssh-config.md` §4 forbids tidying
them into agreement. Build and dry-run are credential-free. Validate reports desired-state,
tool, credential-presence, and DigitalOcean authentication failures together.

Credentials use only `COLORS_PAR_*` and never render. `COLORS_PAR_PROFILE` is
always refused. Keep `compute-prevent-destroy: true` in desired state;
`COLORS_PAR_COMPUTE_PREVENT_DESTROY` is ignored. Explicit `delete` authorizes
manual destruction. `sync` authorizes destruction only after every desired
torrent is complete and the final checksummed rsync succeeds.

The package owns its DigitalOcean template and depends on Green and on ONCE for
the keypair implementation alone. The UI is
not a public service: Transmission RPC binds 127.0.0.1, RPC password auth is
disabled because SSH is the only access boundary, and acceptance opens a
short-lived SSH local forward before curling the web UI. `sync` keeps its own
forward open, prints the UI URL, adds desired magnets, incrementally rsyncs
completed downloads directly into the configured local directory, stops the
daemon for a final checksummed rsync, and only then deletes the deployment.
Failures retain the Droplet and state. Stage names are package-specific
remote-state keys.

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

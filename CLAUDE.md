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

## Architecture and safety

Create is `start -> infrastructure -> ansible-local -> ansible-remote ->
acceptance`. Delete removes the local managed SSH block before destroying the
Droplet. Build and dry-run are credential-free. Validate reports desired-state,
tool, credential-presence, and DigitalOcean authentication failures together.

Credentials use only `COLORS_PAR_*` and never render. `COLORS_PAR_PROFILE` is
always refused. Keep `compute-prevent-destroy: true` in desired state;
`COLORS_PAR_COMPUTE_PREVENT_DESTROY` is ignored. Explicit `delete` authorizes
manual destruction. `sync` authorizes destruction only after every desired
torrent is complete and the final checksummed rsync succeeds.

The package owns its DigitalOcean template and depends only on Green. The UI is
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

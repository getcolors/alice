# alice

A Green Package Skill that provisions one DigitalOcean Droplet, installs
Transmission, manages a local SSH alias, and keeps the web UI private behind an
SSH tunnel.

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

While the tunnel runs, open
`http://127.0.0.1:19091/transmission/web/`. Create performs the same tunneled UI
check before it succeeds. `sync` provisions or resumes the Droplet, prints and
keeps the tunnel URL available, downloads every desired magnet, incrementally
rsyncs the download directory directly into the configured local directory,
then stops Transmission, verifies a final checksummed copy, and destroys the
Droplet. A failure retains the deployment for a retry.

## Install

```sh
npx skills add getcolors/alice
cp .agents/skills/package-alice-green/green green
chmod +x green
```

The deployment launcher is a copy. Re-copy it after every skill update. Desired
state and credentials are documented in
[`references/configuration.md`](skills/package-alice-green/references/configuration.md).
Credentials use ignored `COLORS_PAR_*` exports; never set
`COLORS_PAR_PROFILE`.

## Development

```sh
bb test
bb golden
./scripts/launcher.sh
```

Inspect generated output before accepting golden changes. Tests do not provision
resources.

## License

MIT.

Compute provisioning uses one `colors-compute` node with identifier `0` and
state filename `alice-node-0.tfstate`. Alice supplies the firewall policy and
orders provisioning, Ansible, download verification, and destruction. The
Droplet is named `<profile>-0`.

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

For working-tree dependency tests:

```sh
export COLORS_COMPUTE_LIB_ROOT=/absolute/path/to/colors-compute/green
bb -Sdeps '{:deps {io.github.getcolors/colors-compute {:local/root "/absolute/path/to/colors-compute/green"}}}' test
bb golden
./scripts/launcher.sh
```

Golden and launcher checks honor `COLORS_COMPUTE_LIB_ROOT`. The launcher SHA
remains managed by `bb pin`; development does not invent or change that stamp.

Compute failures identify the failed stage and command, resolved executable,
exit code, and sanitized stderr. For example, an asdf `tofu` shim with no selected
version reports the toolchain failure and suggests `direnv exec . ./green sync`
when running sync. State-read failures and identity mismatches remain distinct.
Only failures before apply say no infrastructure changes were made by that
operation; failures during or after apply advise inspecting state before retry.

Credential-free `build` renders all preview stages under `<workdir>/build/<profile>/`.
Real lifecycle operations keep `<workdir>/<profile>/`. This isolates placeholder
identities and generated previews from live templates, state, and encrypted SSH
authority, so build remains safe after a deployment exists. Preview files are
replaced on subsequent builds; they are never used to provision the deployment.

For working-tree development, the launcher directly honors `ALICE_LIB_ROOT`,
`GREEN_LIB_ROOT`, and `COLORS_COMPUTE_LIB_ROOT` (the latter points to the library's
`green/` directory). No wrapper script is required.

Update dependency pins with `bb pin:dependencies` after committing and pushing
both sibling `green` and `colors-compute` repositories. The task verifies each
checkout is clean, on `main`, uses the expected origin, and matches the current
remote `main` SHA before changing either dependency. It preserves the rest of
`deps.edn`; `bb pin:dependencies:test` checks the refusal paths offline. After
Alice itself is committed and pushed, `bb pin` stamps its launcher separately.

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

OpenTofu runs in `<workdir>/<profile>/0/`. Templates and `.terraform/` remain
after deletion. SSH keys belong to the node: local state is authoritative for
the local backend; remote backends use authoritative S3 key objects. Access
files are refreshed from that authority before application access. The
package-owned SSH config block points to that SDK copy.

S3/R2 store state under `<s3-prefix>/<profile>/alice-node-0.tfstate`. A local
backend stores state and authoritative keys in the node directory, with no S3
requirement. See the configuration reference for remote-backend credentials.

Existing monolithic or shared/node state requires explicit migration. This
adapter does not adopt old keys, registrations, or cloud resources automatically.

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

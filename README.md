# Alice

Private Transmission on one DigitalOcean Droplet, using Python and PocketDeploy.
Alice owns Transmission and download ordering. PocketDeploy owns cloud resources,
state, locking, SSH identity and trust, aliases, and Vault recovery. No Terraform,
OpenTofu, Ansible or Clojure runtime is required.

Install Python 3.11+, uv, OpenSSH and rsync. Run the portable `alice` launcher from
an independent deployment directory containing `colors.yml`. Never reuse a legacy
Terraform deployment directory or infer ownership from an existing resource name.

```sh
alice init
alice plan
alice converge
alice tunnel
alice status
alice sync
alice delete
```

Converge verifies the real Transmission UI and RPC over a loopback-only SSH tunnel.
Open `http://127.0.0.1:19091/transmission/web/` while `alice tunnel` runs. RPC is
never publicly exposed. `sync` converges, adds desired magnets, copies incrementally,
stops Transmission, performs a final checksummed rsync and only then deletes.
Failures retain the deployment. Rsync never deletes local files. An empty magnet
list still stops, copies and deletes. Both `sync` and `delete` require an explicit
`compute-prevent-destroy: false` in YAML; its environment override is ignored.

Interrupting `sync` retains the deployment. A subsequent `delete` can discard it
without waiting for downloads or performing a final copy. SSH cleanup removes
only files with verified generation records; unrecorded files are retained and
reported, never adopted. Changed recorded keys and unsafe paths still block
teardown. `delete --dry-run` includes the SSH cleanup and retention plan.

`ssh`, `ssh-install`, `ssh-uninstall`, `vault-save` and `vault-restore` use shared
PocketDeploy authority. Vault checkpoints contain controller recovery state, not
remote downloads. Keep previous state and encrypted authority until separately
recovered or retired. This port does not adopt old resources.

See [configuration](skills/alice/references/configuration.md) and the example
[colors.yml](colors.yml). Put `export COLORS_PAR_DO_TOKEN=''` in an ignored,
mode-0600 `.envrc.private`, fill it privately and load it into the environment.
Never put tokens in YAML or command arguments.

For source development:

```sh
uv sync --locked --extra test
uv run --locked --extra test pytest
uv build
python3 scripts/check-launcher.py
```

For shared working-tree development, install PocketDeploy editable with
`uv pip install --no-deps -e /absolute/path/to/pocketdeploy` and run `.venv/bin/python -m pytest`. Release
packages and copied launchers pin published commits; updates preserve caller-relative
configuration. Live tests use a separate disposable profile and explicit cloud
authorization. MIT licensed.

See [verification](docs/verification.md) for dated tests and live-check limits.

Failures carry structured, output-free diagnostics: a fixed logical operation,
category, elapsed time and (when available) exit code, signal or HTTP status.
Remote setup diagnostics survive SSH transport; RPC distinguishes malformed
responses, rejection, HTTP, connection and timeout failures; rsync identifies
incremental versus final checksummed copying. Diagnostics never include command
arguments, paths, magnet links, torrent names, session headers or response bodies.
No masking or output excerpts are used, and retry/deletion policy is unchanged.

With `sync --verbose`, fixed stages report starts, completion/failure, elapsed time
and waiting heartbeats on stderr. Remote package, AppArmor, configuration and
service stages stream through the shared pinned SSH transport while one remote
lock covers provisioning. Events are advisory; successful RPC verification still
completes the durable convergence intent. JSON mode retains one final result/error
envelope on stdout; quiet mode suppresses progress.

Sync checks local rsync `--protect-args` support and destination write access before
cloud convergence. An incompatible rsync now fails as `downloads-rsync-preflight`
with an installation hint. Copy failures retain resources, and no raw rsync or
remote command output is exposed.

The devenv shell provides compatible rsync, OpenSSH and Python. Its `alice`
command runs this checkout with locked dependencies while retaining the caller's
working directory for configuration:

```sh
devenv shell --no-tui -- alice --help
```

For shared working-tree development, install PocketDeploy editable as described
above and use `devenv shell --no-tui -- .venv/bin/python -m alice.cli` from this
checkout. The locked `alice` wrapper intentionally restores published dependencies.
Release order is PocketDeploy publication, Alice dependency pin and publication,
then portable launcher pins to the published Alice commit.

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

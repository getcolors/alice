---
name: alice
description: Operate a private Transmission deployment on DigitalOcean using Python and PocketDeploy; manage downloads, verified copies, SSH tunnels and explicit teardown.
---

Read references/configuration.md. Use the bundled standalone `alice` launcher
from the deployment directory; it resolves colors.yml relative to the caller.
Never print credentials, inspect legacy private state or infer ownership from names.
Converge/delete need explicit cloud authorization. Sync includes deletion after a
successful final checksummed copy and requires protection disabled in YAML.
Use plan/status for inspection and explicit vault-save/vault-restore for controller
recovery. Remote downloads are not covered by a controller checkpoint.

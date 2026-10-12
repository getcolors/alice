# Python port verification — 11 October 2026

Alice uses PocketDeploy package `eec9401ab1ff56aad5377ece3b71dc45c4faca0a`.
The portable Alice launcher pins package commit
`22fa6cdbe80e58f7daebc5fa041e42394852e225`; see its actual immutable pin for installation.

## Automated validation

- Alice: 23 tests, source/wheel builds and copied portable launcher passed.
- PocketDeploy: 832 tests, source/wheel builds and 36 copied-launcher checks passed.
- Walter: 93 tests and 23 subtests passed; its ordinary run skipped one privilege
  test, which passed in the separate sudo run (14 tests and two subtests).
- Published package and launcher CI passed before the live verification receipt.

## Live DigitalOcean observations

The separate disposable profile `alice-python-test-20261011` used a 1 GB Ubuntu
24.04 Droplet in `ams3`. No VPC UUID was supplied. Read-only discovery selected
the existing regional default, and convergence recorded that identity before
creating owned resources. The VPC remained externally owned.

Initial convergence and tunneled UI/RPC acceptance passed. Repeat convergence
through the copied published launcher retained Droplet ID `608087650` and took
21 seconds. The firewall allowed SSH and TCP/UDP 51413, with no public RPC rule;
a public TCP 9091 probe was blocked. Remote inspection verified RPC bound only
to `127.0.0.1:9091`. The installed SSH alias authenticated as `ubuntu`.

Deletion protection refused a live delete. A reboot changed the boot ID, pinned
SSH reconnected and Transmission returned active with the same loopback binding.

A synthetic 2,240,000-byte torrent was downloaded using supplied torrent metadata
and an HTTP webseed on the disposable host. Its local SHA-256 was
`49b8825baa8fe696d8df8d8be462c20a91be076111a54797449f2590478efa8c`.
An injected failure only in the final checksummed rsync returned failure after
Transmission stopped. The same Droplet, firewall, SSH authority and state were
retained with zero pending journal steps, allowing a normal retry.

The normal retry completed in 44 seconds, including the final checksummed copy
and deletion. A repeated delete succeeded. Independent DigitalOcean reads
confirmed the Droplet, dedicated firewall and deployment tag were absent, while
the original default VPC remained. The local alias and generated SSH keys were
removed. The final controller status recorded zero resources and pending steps;
the private SQLite deletion receipt and downloaded synthetic file were retained.

The live run found a shared SSH tunnel port probe incorrectly rejecting TCP
TIME_WAIT after its preceding acceptance tunnel. PocketDeploy now uses
SO_REUSEADDR for the probe; real-socket tests verify immediate reuse while an
active listener still fails. The published fix was used for the download test.

## Verification limits

This was synthetic application data on a disposable deployment. The test did not
establish public-swarm magnet metadata discovery: the initial loopback-peer
fixture did not resolve metadata, so the transfer used supplied metainfo and a
webseed. Vault save/restore and migration of legacy Terraform ownership were not
performed. The existing `alice-digitalocean` repository and its old state/keys
were not changed. These observations are dated checks, not monitoring.

## Structured diagnostics update — 11 October 2026

The diagnostics change passed 42 synthetic Alice tests against the shared
PocketDeploy implementation. Coverage includes remote exit preservation,
malformed diagnostic rejection, source-injected remote execution, RPC response
and transport categories, rsync exit/signal attribution, private-value exclusion,
and retention on incremental copy, stop, final copy and download failures.
No live Alice resources were created, modified or deleted for this update.

Published dependency for this update: PocketDeploy
`75b20da90f704a39c745e62ad68907039602bc59`.

The published Alice source `8f27cabc67d351f136cdbcba895837b9bc3fe119`
passed all 42 tests with the locked published dependency and source/wheel builds.
Both portable launcher copies pin that source; the copied-launcher check passed
from an unrelated temporary directory. No live Alice validation was requested.

## Live stage reporting — 12 October 2026

Authorized disposable DigitalOcean sync tested the unpublished Alice source with
editable PocketDeploy streaming support. Used a separate 1 GB Ubuntu 24.04 profile,
an empty magnet list and a 250,000-byte synthetic file seeded after convergence.
The system rsync failed the new `--protect-args` preflight; Nix rsync 3.4.4 passed.
No existing deployment was reused or modified.

Verbose output showed local preflight, cloud readiness (48.2 s), SSH trust (70.7 s),
remote apt update (11.6 s), apt install (104.3 s), AppArmor, configuration, service,
UI/RPC verification, stop, final checksum copy (1.0 s), and teardown (15.9 s).
Remote package-stage heartbeats arrived live under the single provisioning lock.
Sync exited zero; an independent local SHA-256 comparison verified the copied
synthetic file. Subsequent provider inventory confirmed the disposable Droplet,
firewall and tag absent. Local verification found zero resources or pending steps
and removed generated private keys; the deletion receipt and copied fixture remain.

This exercises the empty-magnet final-copy path, not magnet metadata discovery,
swarm transfer, incremental copying, failure injection, or Vault recovery.
Published dependency and portable launcher pins were not changed.

Release validation subsequently pinned Alice to published PocketDeploy
`67d55c4ac51f3b79963de5c098e9a1f72765253b`. PocketDeploy passed 920 tests and
four subtests after integration with current upstream; Alice passed all 59 tests
against that published dependency. Builds succeeded, and `devenv shell --no-tui
-- alice --help` passed using the locked wrapper and compatible Nix rsync.

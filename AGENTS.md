# Alice

Python-only private Transmission controller on DigitalOcean using PocketDeploy.
Read README.md and skills/alice/references/configuration.md before changes.

Changes to Walter and PocketDeploy are allowed when they simplify the overall
codebase. Prefer shared Python code and keep Alice-specific logic small.

Do not inspect legacy `.colors/` or print private configuration, credentials or
state. Explicitly authorized deployment commands may load credentials in process
without displaying them. Never log tokens or pass them on command lines. Live
creation/deletion requires user authorization and a separate disposable profile.
Never adopt resources by name or discard legacy Terraform state or encrypted keys.

Preserve loopback-only RPC, strict host trust, owned aliases, transactional state,
VPC identity binding and teardown protection. Sync must stop Transmission and
complete the final checksummed copy before deletion. Rsync never uses --delete.
An empty magnet list still performs the final copy. Download/copy failures retain
cloud resources. Preserve Ubuntu AppArmor disable-and-verify handling unless the
packaged profile is proven compatible with systemd notification.

Run pytest after implementation changes. Test synthetic failure ordering, credential
exclusion, normalization, pinned SSH and copied launchers before release. Work on
the current branch; commit/push only with user authorization. Pin published dependency
commits and publish Alice before pinning its portable launcher.

Keep both landing-page analytics tags: GA4 G-4VKP1WY4QJ with explicit page_title
matching the decoded title, and Rybbit https://rybbit.getcolors.ai/api/script.js
with data-site-id 9fb9c41a6d49. Never include only one.

---
name: package-alice-green
description: Provision one DigitalOcean Droplet, configure Transmission, manage a local SSH alias, and expose its UI only through an SSH tunnel.
license: MIT
---

# Alice Transmission server

Read [references/configuration.md](references/configuration.md) before changing
state or running a lifecycle command.

## Safety

- Keep credentials out of `colors.yml`; use ignored `COLORS_PAR_*` exports.
- Never set `COLORS_PAR_PROFILE` and never edit generated `.colors/` files.
- Use `build` and `create --dry-run` before a real lifecycle operation.
- Keep `compute-prevent-destroy: true`. The environment override is ignored;
  `delete` authorizes manual destruction and `sync` authorizes only its
  post-rsync cleanup.
- Transmission binds its RPC UI to loopback and relies on SSH as its access
  boundary. Do not expose port 9091 publicly; use the tunnel command.

## Commands

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

While `tunnel` runs, open
`http://127.0.0.1:19091/transmission/web/`. A successful create already performs
this tunnel check before returning. `sync` creates its own tunnel, prints that
URL, adds desired magnets, incrementally rsyncs completed downloads directly
into the configured local directory, verifies a final checksummed copy, and
then destroys the Droplet. Failures retain it for a retry.

Compute provisioning uses the pinned colors-compute library, including SSH key
ownership, default or explicit VPC selection, and remote S3/R2 state. Alice
passes a singleton topology and application firewall policy. Existing monolithic
`<profile>/alice-infrastructure.tfstate` deployments require explicit migration.
The SSH config play remains package-owned and serializes atomic updates.

### Repeated deletion after compute retirement

A repeated `delete` with validated retired compute ownership resumes only the
local generated-file cleanup. It does not require removed SSH keys or contact
the former hosts, DNS, registry, or other application cloud resources. Failed
ownership inspection still stops deletion. Local cleanup preserves unrelated
files and is safe to repeat.

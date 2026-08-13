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
- Keep `compute-prevent-destroy: true`. Lift it only for one authorized delete
  with `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.
- Transmission binds its RPC UI to loopback and relies on SSH as its access
  boundary. Do not expose port 9091 publicly; use the tunnel command.

## Commands

```sh
./green validate
./green build
./green create --dry-run
./green create
./green describe
./green tunnel 19091
./green delete
```

While `tunnel` runs, open
`http://127.0.0.1:19091/transmission/web/`. A successful create already performs
this tunnel check before returning.

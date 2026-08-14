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

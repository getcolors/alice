# Configuration

`colors.yml` is a flat, non-secret map. Required: `profile`,
`digitalocean-account-id` (account UUID) and `digitalocean-region`.
`provider-compute` is `digitalocean`. Size and image default through PocketDeploy.
An optional `digitalocean-vpc-uuid` selects an existing VPC; omission discovers
and validates the regional default. PocketDeploy records the resolved identity
and rejects drift. Alice never creates or owns the VPC.

`digitalocean-token-env` defaults to `COLORS_PAR_DO_TOKEN`. Load that environment
variable privately; tokens never enter desired state. `COLORS_PAR_PROFILE` is
forbidden and `COLORS_PAR_COMPUTE_PREVENT_DESTROY` is ignored. Other supported
fields accept their corresponding COLORS_PAR_ environment override.

| Field | Default / purpose |
|---|---|
| workdir | `.alice`; PocketDeploy local runtime directory |
| state-file | `.colors.sqlite`; durable controller state relative to configuration |
| compute-prevent-destroy | `true`; set false explicitly for delete or sync |
| compute-require-existing-state | refuse a fresh deployment when true |
| alice-ssh-sources | `[0.0.0.0/0]`; restrict to operator CIDRs where possible |
| transmission-rpc-port | `9091`, remote loopback only |
| transmission-tunnel-local-port | `19091`, local loopback only |
| transmission-local-directory | `~/Downloads/alice`; relative paths use configuration directory |
| transmission-magnet-links | `[]`; distinct magnets with 40-character BTIH hashes |

Peer TCP and UDP port 51413 are public. HTTP and RPC ports are not. Shared
`ssh-user`, `ssh-private-key-file`, `ssh-public-key-file`,
`ssh-host-private-key-file`, `ssh-host-public-key-file`, `ssh-known-hosts-file`,
`vault-id`, `vault-state-document-id` and `vault-command` pass to PocketDeploy.

Use `alice -f /path/colors.yml <command>` or `alice <command> -f /path/colors.yml`.
Commands: init, plan, converge, status, ssh, ssh-install, ssh-uninstall, tunnel,
sync, delete, vault-save, vault-restore. Converge/delete support --dry-run.
SSH accepts --ssh-command. Restore accepts --document, --version, --destination
and --overwrite, with the same recovery semantics as PocketDeploy.

Legacy backend, Green, Terraform and passphrase fields are refused. Preserve old
state and authority separately; this version does not import them. SSH keys and
controller state are local private files with explicit Vault checkpoints.

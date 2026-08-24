#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state="$root/test/fixtures/colors.yml"
optout="$root/test/fixtures/optout.yml"
goldens="$root/test/resources/golden"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
accept=0
[ "${1:-}" = --accept ] && accept=1

build() {
  local variant=$1
  local fixture=$2
  shift 2
  (cd "$root" && env ALICE_LIB_ROOT="$root" COLORS_PAR_WORKDIR="$tmp/$variant" "$@" \
    ./green build -f "$fixture" >/dev/null)
  if [ "$accept" = 1 ]; then
    rm -rf "$goldens/$variant"
    mkdir -p "$goldens/$variant"
    cp -r "$tmp/$variant/." "$goldens/$variant/"
    echo "  accepted — $variant"
  else
    diff -qr "$goldens/$variant" "$tmp/$variant"
    echo "  ok — $variant"
  fi
}

# Three variants. `local` and `r2` are the two backends in keygen mode; `optout`
# supplies an explicit key id and must render the historical shape, creating no
# account key resource. The SSH Keypair Standard has two modes and a package
# conforms only if both hold.
build local "$state" COLORS_PAR_PROVIDER_BACKEND=local
build r2 "$state"
build optout "$optout" COLORS_PAR_PROVIDER_BACKEND=local

base="$tmp/local/alice-fixture"
for stage in alice-infrastructure alice-ansible-local alice-ansible-remote alice-acceptance; do
  [ -d "$base/$stage" ] || { echo "golden: missing stage $stage" >&2; exit 1; }
done

infra="$base/alice-infrastructure/main.tf"
grep -q 'resource "digitalocean_droplet" "alice"' "$infra"
grep -q 'vpc_uuid = "00000000-0000-4000-8000-000000000000"' "$infra"
grep -q 'prevent_destroy = true' "$infra"

# Keygen mode owns a profile-named account key resource, references it by
# attribute rather than a literal id, and surfaces its id in state so the
# create preflight can decide ownership (SSH Keypair Standard §4.3, §5).
grep -q 'resource "digitalocean_ssh_key" "machine"' "$infra"
grep -q 'name       = "alice-fixture"' "$infra"
grep -q 'ssh_keys = \[digitalocean_ssh_key.machine.id\]' "$infra"
grep -q 'ssh_key_id = digitalocean_ssh_key.machine.id' "$infra"

# Opt-out mode creates nothing and keeps the literal id it was given.
optout_infra="$tmp/optout/alice-optout-fixture/alice-infrastructure/main.tf"
if grep -q 'digitalocean_ssh_key' "$optout_infra"; then
  echo 'golden: opt-out mode rendered an account key resource' >&2
  exit 1
fi
grep -q 'ssh_keys = \["812184"\]' "$optout_infra"

# The block carries IdentityFile only where the package owns the key (SSH
# Config Standard §3); in opt-out mode the operator has their own arrangements
# and guessing is worse than silence.
local_play="$base/alice-ansible-local/main.yml"
grep -Eq '^[[:space:]]+IdentityFile ~/\.ssh/alice-fixture$' "$local_play"
grep -q 'IdentitiesOnly yes' "$local_play"
grep -q 'insertbefore: BOF' "$local_play"
grep -q 'marker: "# {mark} {{ host_alias }} ANSIBLE MANAGED BLOCK"' "$local_play"
# Anchored on the directive, indented inside the block — the play's own
# comments mention IdentityFile while explaining the wildcard trap.
if grep -Eq '^[[:space:]]+IdentityFile[[:space:]]' \
  "$tmp/optout/alice-optout-fixture/alice-ansible-local/main.yml"; then
  echo 'golden: opt-out mode rendered an IdentityFile it does not own' >&2
  exit 1
fi

# A build that reached the real ~/.ssh would leak the operator's home into
# committed bytes and make the goldens workstation-specific.
if grep -rq "$HOME/.ssh" "$tmp"; then
  echo 'golden: a build rendered a real home directory; it must use the placeholder' >&2
  exit 1
fi

# SSH Config Standard §6: the local stage takes the address, the user and the
# alias as extra-vars, never through Selmer, so its rendered playbook carries no
# address at all. A dotted quad here means a run-time fact was templated.
for variant_base in "$tmp"/*/*; do
  if grep -rEq '([0-9]{1,3}\.){3}[0-9]{1,3}' "$variant_base/alice-ansible-local"; then
    echo "golden: $variant_base rendered an address into the local ssh_config stage" >&2
    exit 1
  fi
done
grep -q 'alice-fixture/alice-infrastructure.tfstate' \
  "$tmp/r2/alice-fixture/alice-infrastructure/backend.tf.json"

play="$base/alice-ansible-remote/main.yml"
grep -q 'transmission-daemon' "$play"
grep -q 'aa-disable /usr/bin/transmission-daemon' "$play"
grep -q '"rpc-bind-address": "127.0.0.1"' "$play"
grep -q '"rpc-authentication-required": false' "$play"
grep -q '"rpc-whitelist-enabled": true' "$play"

grep -q '127.0.0.1:.*:127.0.0.1:' "$base/alice-acceptance/acceptance.sh"
grep -q '/transmission/web/' "$base/alice-acceptance/acceptance.sh"

if grep -rEq 'DIGITALOCEAN_TOKEN\s*=|COLORS_PAR_DO_TOKEN|REPLACE_ME|dop_v1_|github_pat_|ghp_' "$tmp"; then
  echo 'golden: credential-shaped material was rendered' >&2
  exit 1
fi

echo 'all Alice goldens and safety assertions pass'

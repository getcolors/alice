#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state="$root/test/fixtures/colors.yml"
goldens="$root/test/resources/golden"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
accept=0
[ "${1:-}" = --accept ] && accept=1

build() {
  local variant=$1
  shift
  (cd "$root" && env ALICE_LIB_ROOT="$root" COLORS_PAR_WORKDIR="$tmp/$variant" "$@" \
    ./green build -f "$state" >/dev/null)
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

build local COLORS_PAR_PROVIDER_BACKEND=local
build r2

base="$tmp/local/alice-fixture"
for stage in alice-infrastructure alice-ansible-local alice-ansible-remote alice-acceptance; do
  [ -d "$base/$stage" ] || { echo "golden: missing stage $stage" >&2; exit 1; }
done

infra="$base/alice-infrastructure/main.tf"
grep -q 'resource "digitalocean_droplet" "alice"' "$infra"
grep -q 'vpc_uuid = "00000000-0000-4000-8000-000000000000"' "$infra"
grep -q 'prevent_destroy = true' "$infra"
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

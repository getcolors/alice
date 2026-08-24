#!/usr/bin/env bash
set -euo pipefail

alias_name='alice-optout-fixture'
local_port='19091'
remote_port='9091'
ssh_config="$HOME/.ssh/config"
control_path=$(mktemp -u "${TMPDIR:-/tmp}/alice-acceptance.XXXXXX")

cleanup() {
  ssh -o IgnoreUnknown=UseKeychain -F "$ssh_config" \
    -S "$control_path" -O exit -- "$alias_name" \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

service_state=$(ssh -o IgnoreUnknown=UseKeychain -F "$ssh_config" \
  -- "$alias_name" \
  systemctl is-active transmission-daemon)
test "$service_state" = active

ssh -o IgnoreUnknown=UseKeychain -F "$ssh_config" \
  -o BatchMode=yes \
  -o ExitOnForwardFailure=yes \
  -o ControlMaster=yes \
  -o ControlPath="$control_path" \
  -o ControlPersist=no \
  -fN -L "127.0.0.1:${local_port}:127.0.0.1:${remote_port}" \
  -- "$alias_name"

for _ in $(seq 1 20); do
  if curl -fsS "http://127.0.0.1:${local_port}/transmission/web/" >/dev/null; then
    echo "Transmission UI is reachable through the SSH tunnel"
    exit 0
  fi
  sleep 1
done

echo "Transmission UI did not answer through the SSH tunnel" >&2
exit 1

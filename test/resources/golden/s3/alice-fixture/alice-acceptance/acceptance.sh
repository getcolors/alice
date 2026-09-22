#!/usr/bin/env bash
set -euo pipefail

alias_name='alice-fixture'
local_port='19091'
remote_port='9091'
ssh_config="$HOME/.ssh/config"
identity='/home/build-placeholder/compute/alice-fixture/0/identity.pub'
agent='/home/build-placeholder/agent.sock'
control_dir=$(mktemp -d "${TMPDIR:-/tmp}/alice-acceptance.XXXXXX")
control_path="$control_dir/control.sock"
tunnel_pid=""

cleanup() {
  if [ -n "$tunnel_pid" ]; then
    kill "$tunnel_pid" >/dev/null 2>&1 || true
    wait "$tunnel_pid" >/dev/null 2>&1 || true
  fi
  rm -rf "$control_dir"
}
trap cleanup EXIT

service_state=$(ssh -o ControlMaster=no -o ControlPersist=no -S none -i "$identity" -o "IdentityAgent=$agent" -o IdentitiesOnly=yes -o ForwardAgent=no -o IgnoreUnknown=UseKeychain -F "$ssh_config" \
  -- "$alias_name" \
  systemctl is-active transmission-daemon)
test "$service_state" = active

ssh -i "$identity" -o "IdentityAgent=$agent" -o IdentitiesOnly=yes -o ForwardAgent=no -o IgnoreUnknown=UseKeychain -F "$ssh_config" \
  -o BatchMode=yes \
  -o ExitOnForwardFailure=yes \
  -o ControlMaster=yes -o "ControlPath=$control_path" -o ControlPersist=no \
  -N -L "127.0.0.1:${local_port}:127.0.0.1:${remote_port}" \
  -- "$alias_name" &
tunnel_pid=$!

for _ in $(seq 1 20); do
  if ssh -o IgnoreUnknown=UseKeychain -F "$ssh_config" -S "$control_path" -O check -- "$alias_name" >/dev/null 2>&1 && \
     curl -fsS "http://127.0.0.1:${local_port}/transmission/web/" >/dev/null; then
    echo "Transmission UI is reachable through the SSH tunnel"
    exit 0
  fi
  sleep 1
done

echo "Transmission UI did not answer through the SSH tunnel" >&2
exit 1

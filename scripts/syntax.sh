#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
alice_syntax_tmp=$(mktemp -d)
trap 'rm -rf "$alice_syntax_tmp"' EXIT
(cd "$root" && ALICE_LIB_ROOT="$root" COLORS_PAR_WORKDIR="$alice_syntax_tmp" ./green build -f test/fixtures/colors.yml >/dev/null)
(cd "$alice_syntax_tmp/alice-fixture/alice-ansible-remote" && ansible-playbook --syntax-check -i inventory.json main.yml)
(cd "$alice_syntax_tmp/alice-fixture/alice-ansible-local" && ansible-playbook --syntax-check -i inventory.ini main.yml)
bash -n "$alice_syntax_tmp/alice-fixture/alice-acceptance/acceptance.sh"

#!/usr/bin/env bash
# Run a launcher against a working colors-compute checkout when requested.
set -euo pipefail
launcher=$1
shift
if [ -n "${COLORS_COMPUTE_LIB_ROOT:-}" ]; then
  spec=$(bb -e '(println (pr-str {:deps {(symbol "io.github.getcolors/colors-compute") {:local/root (System/getenv "COLORS_COMPUTE_LIB_ROOT")}}}))')
  exec bb -Sdeps "$spec" "$launcher" "$@"
fi
exec "$launcher" "$@"

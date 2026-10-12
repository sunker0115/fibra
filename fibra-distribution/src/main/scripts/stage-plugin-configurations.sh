#!/usr/bin/env bash
set -euo pipefail

# The dependency copy execution owns the package list. Inspect only its staged JARs.
stage_root=$1
temporary_root=$(mktemp -d)
trap 'rm -rf "$temporary_root"' EXIT
found=false
for jar_path in "$stage_root"/plugins/*/lib/main.jar; do
  [[ -f "$jar_path" ]] || continue
  found=true
  jar_path=$(cd "$(dirname "$jar_path")" && pwd)/main.jar
  jar tf "$jar_path" > "$temporary_root/entries"
  count=$(awk '$0 == "META-INF/fibra/config.json" {n++} END {print n+0}' "$temporary_root/entries")
  [[ "$count" == 1 ]] || { echo "Expected one fixed configuration entry: $jar_path" >&2; exit 1; }
  if grep -Fx 'META-INF/fibra/config.json/' "$temporary_root/entries" >/dev/null; then
    echo "Configuration entry is a directory: $jar_path" >&2
    exit 1
  fi
  extraction=$(mktemp -d "$temporary_root/extract.XXXXXX")
  (cd "$extraction" && jar xf "$jar_path" META-INF/fibra/config.json)
  [[ -f "$extraction/META-INF/fibra/config.json" && ! -L "$extraction/META-INF/fibra/config.json" ]]
  cp "$extraction/META-INF/fibra/config.json" "$(dirname "$jar_path")/../fibra-config.json"
done
[[ "$found" == true ]] || { echo 'No staged plugin JARs' >&2; exit 1; }

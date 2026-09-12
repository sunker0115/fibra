#!/bin/sh
set -eu

distribution_root=$1
node_input=$2
rg_input=$3
bash_input=$4
runtime_bin="$distribution_root/runtime/bin"

resolve_executable() {
  candidate=$1
  case "$candidate" in
    */*) resolved=$candidate ;;
    *) resolved=$(command -v "$candidate" || true) ;;
  esac
  if [ -z "${resolved:-}" ] || [ ! -f "$resolved" ] || [ ! -x "$resolved" ]; then
    echo "无法装入目标平台运行件：$candidate" >&2
    exit 1
  fi
  printf '%s\n' "$resolved"
}

node_source=$(resolve_executable "$node_input")
rg_source=$(resolve_executable "$rg_input")
bash_source=$(resolve_executable "$bash_input")
mkdir -p "$runtime_bin"
cp -L "$node_source" "$runtime_bin/node"
cp -L "$rg_source" "$runtime_bin/rg"
cp -L "$bash_source" "$runtime_bin/bash"
chmod 0755 "$runtime_bin/node" "$runtime_bin/rg" "$runtime_bin/bash"
chmod 0755 "$distribution_root/bin/fibra"

{
  printf 'os=%s\n' "$(uname -s)"
  printf 'arch=%s\n' "$(uname -m)"
  printf 'node=%s\n' "$("$runtime_bin/node" --version)"
  printf 'ripgrep=%s\n' "$("$runtime_bin/rg" --version | sed -n '1p')"
  printf 'bash=%s\n' "$("$runtime_bin/bash" --version | sed -n '1p')"
} > "$distribution_root/runtime/platform.properties"

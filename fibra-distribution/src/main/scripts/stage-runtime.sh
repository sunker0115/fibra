#!/bin/sh
set -eu

distribution_root=$1
node_input=$2
rg_input=$3
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
bash_source=/bin/bash
if [ ! -f "$bash_source" ] || [ ! -x "$bash_source" ]; then
  echo "目标平台缺少 Bash：$bash_source" >&2
  exit 1
fi
mkdir -p "$runtime_bin"
cp -L "$node_source" "$runtime_bin/node"
cp -L "$rg_source" "$runtime_bin/rg"
{
  printf '#!/bin/sh\n'
  printf 'exec "%s" "$@"\n' "$bash_source"
} > "$runtime_bin/bash"
chmod 0755 "$runtime_bin/node" "$runtime_bin/rg" "$runtime_bin/bash"
chmod 0755 "$distribution_root/bin/fibra"

node_version=$("$runtime_bin/node" --version)
rg_version=$("$runtime_bin/rg" --version)
bash_version=$("$bash_source" --version)
{
  printf 'os=%s\n' "$(uname -s)"
  printf 'arch=%s\n' "$(uname -m)"
  printf 'node=%s\n' "$node_version"
  printf 'ripgrep=%s\n' "$(printf '%s\n' "$rg_version" | sed -n '1p')"
  printf 'bashExecutable=%s\n' "$bash_source"
  printf 'bash=%s\n' "$(printf '%s\n' "$bash_version" | sed -n '1p')"
} > "$distribution_root/runtime/platform.properties"

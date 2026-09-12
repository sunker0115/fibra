#!/usr/bin/env bash
set -euo pipefail

readonly stage_script=$1
readonly temporary_root=$(mktemp -d)
trap 'rm -rf "$temporary_root"' EXIT

fail() {
  echo "发行运行件装配测试失败：$*" >&2
  exit 1
}

make_executable() {
  local path=$1
  local version=$2
  local status=$3
  printf '#!/bin/sh\nprintf "%%s\\n" "%s"\nexit %s\n' "$version" "$status" > "$path"
  chmod 0755 "$path"
}

make_distribution() {
  local path=$1
  mkdir -p "$path/bin"
  printf '#!/bin/sh\n' > "$path/bin/fibra"
  chmod 0755 "$path/bin/fibra"
}

make_distribution "$temporary_root/distribution"
make_executable "$temporary_root/node" v-test 0
make_executable "$temporary_root/rg" 'ripgrep-test 1.0' 0

"$stage_script" "$temporary_root/distribution" "$temporary_root/node" "$temporary_root/rg"
grep -Fx 'node=v-test' "$temporary_root/distribution/runtime/platform.properties" >/dev/null ||
  fail "未记录 Node 版本"
grep -Fx 'ripgrep=ripgrep-test 1.0' "$temporary_root/distribution/runtime/platform.properties" >/dev/null ||
  fail "未记录 ripgrep 版本"

make_executable "$temporary_root/node-failure" ignored 9
make_distribution "$temporary_root/node-failure-distribution"
if "$stage_script" "$temporary_root/node-failure-distribution" \
    "$temporary_root/node-failure" "$temporary_root/rg" >/dev/null 2>&1; then
  fail "Node 版本探测失败仍返回成功"
fi

make_executable "$temporary_root/rg-failure" ignored 8
make_distribution "$temporary_root/rg-failure-distribution"
if "$stage_script" "$temporary_root/rg-failure-distribution" \
    "$temporary_root/node" "$temporary_root/rg-failure" >/dev/null 2>&1; then
  fail "ripgrep 版本探测失败仍返回成功"
fi

#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd)"
diagnostics_root="${FIBRA_CI_DIAGNOSTICS_DIR:?FIBRA_CI_DIAGNOSTICS_DIR must be set}"
fixture="$repository_root/verification/ci/HangingJvmFixture.java"
wrapper="$repository_root/scripts/run-ci-with-jvm-diagnostics.sh"
temporary_root="$(mktemp -d)"
pid_file="$temporary_root/pids.txt"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
timeout_dir="$diagnostics_root/short-timeout-$run_id"
normal_dir="$diagnostics_root/normal-exit-$run_id"

cleanup() {
  while IFS= read -r pid; do
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] && kill -KILL "$pid" 2>/dev/null || true
  done < "$pid_file" 2>/dev/null || true
  rm -rf "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "$1" >&2
  exit 1
}

wait_for_pid_file() {
  local attempt
  for attempt in {1..50}; do
    [[ -s "$pid_file" ]] && return
    sleep 0.1
  done
  fail "挂起 fixture 未写入 PID"
}

assert_gone() {
  local pid="$1" attempt
  for attempt in {1..30}; do
    kill -0 "$pid" 2>/dev/null || return
    sleep 0.1
  done
  fail "短超时清理后仍存在进程 $pid"
}

mkdir -p "$diagnostics_root"
set +e
FIBRA_CI_DIAGNOSTICS_DIR="$timeout_dir" \
FIBRA_CI_DIAGNOSTICS_INITIAL_DELAY_SECONDS=1 \
FIBRA_CI_DIAGNOSTICS_INTERVAL_SECONDS=1 \
FIBRA_CI_COMMAND_TIMEOUT_SECONDS=2 \
FIBRA_CI_TERMINATION_GRACE_SECONDS=1 \
  "$wrapper" java "$fixture" "$pid_file"
timeout_status=$?
set -e

[[ "$timeout_status" -eq 124 ]] || fail "短超时门禁退出码不是 124：$timeout_status"
[[ -s "$timeout_dir/command-timeout.txt" ]] || fail "短超时门禁缺少 timeout marker"
sample_dir="$(find "$timeout_dir" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
[[ -n "$sample_dir" && -s "$sample_dir/processes.txt" ]] || fail "短超时门禁缺少进程快照"
jvm_dump="$(find "$sample_dir" -type f -name 'jvm-*.txt' -size +0c | head -n 1)"
[[ -n "$jvm_dump" ]] || fail "短超时门禁缺少 JVM dump"
grep -q 'fibra-short-timeout-fixture' "$jvm_dump" || fail "JVM dump 未识别挂起 fixture"

wait_for_pid_file
pids=()
while IFS= read -r pid; do
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || fail "挂起 fixture 写入了无效 PID：$pid"
  pids+=("$pid")
done < "$pid_file"
[[ "${#pids[@]}" -eq 2 ]] || fail "挂起 fixture 未写入两个 PID"
[[ "${pids[0]}" != "${pids[1]}" ]] || fail "挂起 fixture 写入了重复 PID"
for pid in "${pids[@]}"; do
  assert_gone "$pid"
done
: > "$pid_file"

set +e
FIBRA_CI_DIAGNOSTICS_DIR="$normal_dir" \
FIBRA_CI_DIAGNOSTICS_INITIAL_DELAY_SECONDS=1 \
  "$wrapper" sh -c 'exit 7'
normal_status=$?
set -e
[[ "$normal_status" -eq 7 ]] || fail "正常命令退出码未透传：$normal_status"
[[ ! -e "$normal_dir/command-timeout.txt" ]] || fail "正常命令产生了 timeout marker"

echo "短超时诊断门禁验证通过"

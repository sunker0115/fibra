#!/usr/bin/env bash
set -euo pipefail

diagnostics_dir="${FIBRA_CI_DIAGNOSTICS_DIR:?FIBRA_CI_DIAGNOSTICS_DIR must be set}"
initial_delay_seconds="${FIBRA_CI_DIAGNOSTICS_INITIAL_DELAY_SECONDS:-180}"
sample_interval_seconds="${FIBRA_CI_DIAGNOSTICS_INTERVAL_SECONDS:-60}"
command_timeout_seconds="${FIBRA_CI_COMMAND_TIMEOUT_SECONDS:-720}"
termination_grace_seconds="${FIBRA_CI_TERMINATION_GRACE_SECONDS:-10}"
timeout_marker="$diagnostics_dir/command-timeout.txt"

if (( $# == 0 )); then
  echo "usage: $0 <command> [args...]" >&2
  exit 64
fi

mkdir -p "$diagnostics_dir"

run_with_timeout() {
  local seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout --signal=TERM --kill-after=5s "${seconds}s" "$@"
  else
    "$@"
  fi
}

descendant_pids() {
  local root_pid="$1"
  local -a pending=("$root_pid")
  local parent_pid child_pid

  while (( ${#pending[@]} > 0 )); do
    parent_pid="${pending[0]}"
    pending=("${pending[@]:1}")
    printf '%s\n' "$parent_pid"
    while IFS= read -r child_pid; do
      [[ -n "$child_pid" ]] && pending+=("$child_pid")
    done < <(pgrep -P "$parent_pid" 2>/dev/null || true)
  done
}

capture_sample() {
  local sample_number="$1"
  local timestamp sample_dir pid command_line dump_file

  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  sample_dir="$diagnostics_dir/$(printf '%03d-%s' "$sample_number" "$timestamp")"
  mkdir -p "$sample_dir"
  if ! ps -eo pid=,ppid=,stat=,etime=,args= > "$sample_dir/processes.txt" 2>&1; then
    printf '\nprocess snapshot failed\n' >> "$sample_dir/processes.txt"
  fi

  while IFS= read -r pid; do
    command_line="$(ps -p "$pid" -o args= 2>/dev/null || true)"
    [[ "$command_line" == *java* ]] || continue
    dump_file="$sample_dir/jvm-$pid.txt"
    {
      printf 'pid=%s\ncommand=%s\n\n' "$pid" "$command_line"
      if ! run_with_timeout 15 jcmd "$pid" Thread.print -l; then
        printf '\njcmd failed; trying jstack -l\n\n'
        run_with_timeout 15 jstack -l "$pid"
      fi
    } > "$dump_file" 2>&1 || true
  done < <(descendant_pids "$command_pid")

  echo "Captured JVM diagnostic sample $sample_number at $timestamp"
}

terminate_command_tree() {
  local -a pids=()
  local pid index

  if [[ -n "${command_group_pid:-}" ]]; then
    kill -TERM -- "-$command_group_pid" 2>/dev/null || true
    sleep "$termination_grace_seconds"
    kill -KILL -- "-$command_group_pid" 2>/dev/null || true
    return
  fi

  # macOS lacks setsid; this fallback keeps local script checks usable.
  while IFS= read -r pid; do
    pids+=("$pid")
  done < <(descendant_pids "$command_pid")

  for (( index=${#pids[@]}-1; index>=0; index-- )); do
    kill -TERM "${pids[$index]}" 2>/dev/null || true
  done
  sleep "$termination_grace_seconds"
  for (( index=${#pids[@]}-1; index>=0; index-- )); do
    kill -KILL "${pids[$index]}" 2>/dev/null || true
  done
}

watch_command() {
  local sample_number=1
  local started_at elapsed_seconds

  started_at="$(date +%s)"
  sleep "$initial_delay_seconds"
  while kill -0 "$command_pid" 2>/dev/null; do
    capture_sample "$sample_number"
    sample_number=$((sample_number + 1))
    elapsed_seconds=$(( $(date +%s) - started_at ))
    if (( elapsed_seconds >= command_timeout_seconds )); then
      printf 'command exceeded %s seconds and was terminated\n' "$command_timeout_seconds" > "$timeout_marker"
      terminate_command_tree
      return
    fi
    sleep "$sample_interval_seconds"
  done
}

stop_watcher() {
  if [[ -n "${watcher_pid:-}" ]]; then
    kill "$watcher_pid" 2>/dev/null || true
    wait "$watcher_pid" 2>/dev/null || true
  fi
}

forward_signal() {
  terminate_command_tree
  stop_watcher
}

if command -v setsid >/dev/null 2>&1; then
  setsid "$@" &
  command_pid=$!
  command_group_pid="$command_pid"
else
  "$@" &
  command_pid=$!
  command_group_pid=""
fi
watch_command &
watcher_pid=$!
trap stop_watcher EXIT
trap forward_signal INT TERM

set +e
wait "$command_pid"
command_status=$?
set -e
if [[ -f "$timeout_marker" ]]; then
  wait "$watcher_pid" 2>/dev/null || true
  command_status=124
else
  stop_watcher
fi
exit "$command_status"

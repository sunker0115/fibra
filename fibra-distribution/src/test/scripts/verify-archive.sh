#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

readonly archive=$1
readonly version=$2
readonly temporary_root=$(mktemp -d)
readonly archive_copy="$temporary_root/input/fibra.zip"
readonly extract_root="$temporary_root/extracted"
readonly working_directory="$temporary_root/external working directory"
readonly install_root="$extract_root/fibra-$version"
readonly launcher="$install_root/bin/fibra"
readonly repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
host_pid=

cleanup() {
  if [[ -n "$host_pid" ]] && kill -0 "$host_pid" 2>/dev/null; then
    kill -TERM "$host_pid" 2>/dev/null || true
    for _ in {1..100}; do
      kill -0 "$host_pid" 2>/dev/null || break
      sleep 0.05
    done
    kill -KILL "$host_pid" 2>/dev/null || true
    wait "$host_pid" 2>/dev/null || true
  fi
  rm -rf "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "发行 ZIP 验证失败：$*" >&2
  exit 1
}

assert_contains() {
  local file=$1
  local expected=$2
  grep -F -- "$expected" "$file" >/dev/null || fail "$file 缺少 $expected"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

run_cli() {
  local output=$1
  shift
  (
    cd "$working_directory"
    "$launcher" "$@"
  ) > "$output" 2> "$output.stderr"
}

await_file() {
  local file=$1
  local attempt
  for attempt in {1..200}; do
    [[ -f "$file" ]] && return
    sleep 0.05
  done
  fail "等待文件超时：$file"
}

await_stopped() {
  local pid=$1
  local attempt
  for attempt in {1..200}; do
    if ! kill -0 "$pid" 2>/dev/null; then return; fi
    sleep 0.05
  done
  fail "受管进程未停止：$pid"
}

[[ -f "$archive" ]] || fail "缺少 ZIP：$archive"
mkdir -p "$(dirname "$archive_copy")" "$extract_root" "$working_directory"
cp "$archive" "$archive_copy"
zip_entries="$temporary_root/zip.entries"
unzip -Z1 "$archive_copy" > "$zip_entries"
if grep -Eq '(^/|(^|/)\.\.(/|$)|(^|/)(target|\.DS_Store)(/|$))' "$zip_entries"; then
  fail "发行 ZIP 包含非法路径或构建残留"
fi
(
  cd "$extract_root"
  unzip -q "$archive_copy"
)
[[ -x "$launcher" ]] || fail "bin/fibra 不可执行"
[[ ! -e "$install_root/data" ]] || fail "发行包不得预置 data 目录"
[[ -f "$install_root/LICENSE" ]] || fail "发行包缺少 LICENSE"
[[ -f "$install_root/THIRD_PARTY_NOTICES.md" ]] || fail "发行包缺少第三方声明"
if find "$install_root" -type l -print -quit | grep -q .; then
  fail "发行包不得包含符号链接"
fi
if LC_ALL=C grep -R -a -F -m 1 -- "$repository_root" "$install_root" >/dev/null; then
  fail "发行包泄漏仓库绝对路径"
fi
for executable in node rg bash; do
  [[ -x "$install_root/runtime/bin/$executable" ]] || fail "缺少运行件：$executable"
done

cli_jars=("$install_root"/lib/fibra-cli-*.jar)
[[ ${#cli_jars[@]} -eq 1 ]] || fail "lib 应恰好包含一个 fibra-cli JAR"
host_entries="$temporary_root/host.entries"
jar tf "${cli_jars[0]}" > "$host_entries"
if grep -Eq '^META-INF/fibra/plugin.yaml$|^com/sstlfsj/fibra/plugins/(fs|subprocess|shell|storage)/' "$host_entries"; then
  fail "正式插件被打入宿主 JAR"
fi
if find "$install_root/lib" -type f \( \
    -name 'fibra-fs-*.jar' -o -name 'fibra-fs-local-*.jar' -o \
    -name 'fibra-tool-fs-*.jar' -o -name 'fibra-tool-fs-search-*.jar' -o \
    -name 'fibra-subprocess-*.jar' -o -name 'fibra-subprocess-local-*.jar' -o \
    -name 'fibra-shell-*.jar' -o -name 'fibra-shell-local-*.jar' -o \
    -name 'fibra-tool-shell-*.jar' -o -name 'fibra-storage-*.jar' -o \
    -name 'fibra-storage-json-*.jar' -o -name 'fibra-tool-storage-*.jar' \) \
    -print -quit | grep -q .; then
  fail "动态插件 JAR 不得进入宿主 lib"
fi

for artifact in fibra-fs fibra-fs-local fibra-tool-fs fibra-tool-fs-search \
    fibra-subprocess fibra-subprocess-local fibra-shell fibra-shell-local \
    fibra-tool-shell fibra-storage fibra-storage-json fibra-tool-storage; do
  [[ -f "$install_root/plugins/$artifact/plugin.properties" ]] || fail "缺少 $artifact 包描述"
  [[ -f "$install_root/plugins/$artifact/lib/main.jar" ]] || fail "缺少 $artifact 主 JAR"
done

plugins_output="$temporary_root/plugins.json"
tools_output="$temporary_root/tools.json"
run_cli "$plugins_output" plugins list
[[ -d "$install_root/data/profiles/default/state" ]] || fail "首次启动未创建 Profile 状态"
[[ -f "$install_root/data/profiles/default/state/target.json" ]] || fail "首次启动未保存完整目标"
assert_contains "$plugins_output" '"id":"fibra-fs"'
assert_contains "$plugins_output" '"id":"fibra-tool-fs-search"'
assert_contains "$plugins_output" '"id":"fibra-subprocess-local"'
assert_contains "$plugins_output" '"id":"fibra-shell-local"'
assert_contains "$plugins_output" '"id":"fibra-storage-json"'
assert_contains "$plugins_output" '"id":"storage-tools"'

run_cli "$tools_output" tools list
for tool in '"provider":"fs-tools"' '"provider":"search-tools"' \
    '"provider":"shell-tools"' '"provider":"storage-tools"'; do
  assert_contains "$tools_output" "$tool"
done

write_output="$temporary_root/write.json"
read_output="$temporary_root/read.json"
grep_output="$temporary_root/grep.json"
shell_output="$temporary_root/shell.json"
put_output="$temporary_root/put.json"
load_output="$temporary_root/load.json"
run_cli "$write_output" tools invoke fs-tools write --input \
  '{"path":"acceptance/note.txt","content":"needle from distribution"}'
assert_contains "$write_output" '"operation":"create"'
run_cli "$read_output" tools invoke fs-tools read --input '{"path":"acceptance/note.txt"}'
assert_contains "$read_output" '"text":"needle from distribution"'
run_cli "$grep_output" tools invoke search-tools grep --input '{"pattern":"needle","path":"."}'
assert_contains "$grep_output" '"path":"./acceptance/note.txt"'
run_cli "$shell_output" tools invoke shell-tools bash --input \
  "{\"command\":\"printf shell-from-distribution\",\"workdir\":\"$working_directory\",\"timeoutMs\":5000}"
assert_contains "$shell_output" '"exitCode":0'
assert_contains "$shell_output" '"text":"shell-from-distribution"'
run_cli "$put_output" tools invoke storage-tools put --input '{"key":"theme","value":"dark"}'
assert_contains "$put_output" '"theme":"dark"'
run_cli "$load_output" tools invoke storage-tools load --input '{}'
assert_contains "$load_output" '"theme":"dark"'

repl_output="$temporary_root/storage-repl.log"
(
  cd "$working_directory"
  printf '%s\n' \
    "tools invoke storage-tools put --input '{\"key\":\"language\",\"value\":\"zh-CN\"}'" \
    'tools invoke storage-tools changes --input {}' \
    'quit' | "$launcher" repl
) > "$repl_output" 2> "$repl_output.stderr"
assert_contains "$repl_output" '"key":"language"'
assert_contains "$repl_output" '"operation":"PUT"'

target="$install_root/data/profiles/default/state/target.json"
target_before=$(sha256_file "$target")
mv "$install_root/plugins" "$temporary_root/candidate-plugins-moved"
restored_plugins="$temporary_root/restored-plugins.json"
restored_read="$temporary_root/restored-read.json"
restored_load="$temporary_root/restored-load.json"
run_cli "$restored_plugins" plugins list
run_cli "$restored_read" tools invoke fs-tools read --input '{"path":"acceptance/note.txt"}'
run_cli "$restored_load" tools invoke storage-tools load --input '{}'
assert_contains "$restored_read" '"text":"needle from distribution"'
assert_contains "$restored_load" '"theme":"dark"'
assert_contains "$restored_load" '"language":"zh-CN"'
target_after=$(sha256_file "$target")
[[ "$target_before" == "$target_after" ]] || fail "候选插件目录变化改写了已保存目标"
mv "$temporary_root/candidate-plugins-moved" "$install_root/plugins"

variant_root="$temporary_root/fibra-fs-local-variant"
cp -R "$install_root/plugins/fibra-fs-local" "$variant_root"
printf 'distribution upgrade variant\n' > "$temporary_root/variant.marker"
jar uf "$variant_root/lib/main.jar" -C "$temporary_root" variant.marker
mutation_output="$temporary_root/mutations.log"
(
  cd "$working_directory"
  printf '%s\n' \
    'plugins list' \
    "plugins upgrade $variant_root" \
    'plugins list' \
    'plugins disable fs-tools' \
    'plugins list' \
    'plugins enable fs-tools' \
    'quit' | "$launcher" repl
) > "$mutation_output" 2> "$mutation_output.stderr"
for instance in subprocess-provider search-tools shell-provider shell-tools \
    storage-provider storage-tools; do
  identities=$(grep -o "\"id\":\"$instance\",\"identity\":[^,}]*" "$mutation_output" | sort -u | wc -l | tr -d ' ')
  [[ "$identities" == 1 ]] || fail "无关实例 $instance 在升级或停用期间被替换"
done
fs_provider_identities=$(grep -o '"id":"fs-provider","identity":[^,}]*' \
  "$mutation_output" | sort -u | wc -l | tr -d ' ')
[[ "$fs_provider_identities" == 2 ]] || fail "fibra-fs-local 升级未替换目标实例"
fs_tool_states=$(grep -o '"definition":"tool-fs"[^}]*"id":"fs-tools"[^}]*' "$mutation_output")
grep -F '"enabled":false' <<< "$fs_tool_states" >/dev/null || fail "fs-tools 停用未生效"
grep -F '"observed":false' <<< "$fs_tool_states" >/dev/null || fail "fs-tools 停用后仍在运行"
last_fs_tool_state=$(tail -n 1 <<< "$fs_tool_states")
[[ "$last_fs_tool_state" == *'"enabled":true'* && "$last_fs_tool_state" == *'"observed":true'* ]] ||
  fail "fs-tools 未恢复启用"

entered="$temporary_root/shutdown-entered"
process_ids="$temporary_root/shutdown-pids"
shutdown_output="$temporary_root/shutdown.json"
shutdown_command="sleep 60 & leaf=\$!; printf '%s %s %s\\n' \$\$ \$PPID \$leaf > '$process_ids'; : > '$entered'; wait \$leaf"
(
  cd "$working_directory"
  exec "$launcher" tools invoke shell-tools bash --input \
    "{\"command\":\"$shutdown_command\",\"workdir\":\"$working_directory\",\"timeoutMs\":60000}"
) > "$shutdown_output" 2> "$shutdown_output.stderr" &
host_pid=$!
await_file "$entered"
read -r payload_pid supervisor_pid leaf_pid < "$process_ids"
kill -TERM "$host_pid"
shutdown_deadline="$temporary_root/shutdown-deadline-exceeded"
(
  sleep 10
  if kill -0 "$host_pid" 2>/dev/null; then
    : > "$shutdown_deadline"
    kill -KILL "$host_pid" 2>/dev/null || true
  fi
) &
deadline_pid=$!
set +e
wait "$host_pid"
host_status=$?
kill "$deadline_pid" 2>/dev/null
wait "$deadline_pid" 2>/dev/null
set -e
host_pid=
[[ ! -e "$shutdown_deadline" ]] || fail "SIGTERM 后宿主未在 10 秒内排空退出"
[[ "$host_status" == 0 || "$host_status" == 143 ]] || fail "SIGTERM 后宿主退出码异常：$host_status"
await_stopped "$payload_pid"
await_stopped "$supervisor_pid"
await_stopped "$leaf_pid"

echo "发行 ZIP 仓库外验证通过：$archive"

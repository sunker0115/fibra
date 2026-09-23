#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

readonly maven_executable="${MVN:-mvn}"
readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly fixture="$repository_root/fibra-distribution/src/test/consumers"
production_modules=()
consumer_byte_compared_modules=()
while IFS= read -r module; do
  production_modules+=("$module")
  case "$module" in
    fibra-bom|fibra-runtime-java|fibra-runtime-node|fibra-client-protocol|fibra-plugins/*)
      consumer_byte_compared_modules+=("$module")
      ;;
  esac
done < <("$repository_root/scripts/release-maven-modules.sh")
readonly -a production_modules consumer_byte_compared_modules
readonly module_list="$("$repository_root/scripts/release-maven-modules.sh" csv)"
readonly forbidden_entry_pattern='(^|/)(verification|fixture|fixtures|fibra-runtime-host|fibra-runtime-client|client-runtime|client-runtime-web|client-react|browser([._-]?(runner|runtime|loader))?|web[._-]?loader|client[._-]?runner|playwright)([/.]|$)|(^|/)(ArtifactRuntime|ExecutionRuntime|PluginRuntimeAdapter|ArtifactPackage|PluginArtifactProbe|ReplaceConfigContext)(\$|\.|/)|(^|[^[:alnum:]_])react([^[:alnum:]_]|$)|(^|[^[:alnum:]_])transport([^[:alnum:]_]|$)|(^|/)(plugin[.]properties|[^/]+[.]artifacts[.]yaml)$'
readonly forbidden_text_pattern='(PluginRuntimeAdapter|ArtifactRuntime|ExecutionRuntime|ReplaceConfigContext|ArtifactPackage|PluginArtifactProbe|fibra-runtime-host|fibra-runtime-client|client-runtime-web|client-runtime|client-react|playwright|web[[:space:]_.-]*loader|client[[:space:]_.-]*runner|(^|[^[:alnum:]_])react([^[:alnum:]_]|$)|(^|[^[:alnum:]_])transport[[:space:]_.-]*(adapter|runner|carrier|implementation|runtime|client|gateway|channel)([^[:alnum:]_]|$))'

read_property() {
  local name="$1"
  sed -n "s:.*<$name>\([^<]*\)</$name>.*:\1:p" "$repository_root/pom.xml"
}

module_packaging() {
  local packaging
  packaging="$(sed -n 's:.*<packaging>\([^<]*\)</packaging>.*:\1:p' \
    "$repository_root/$1/pom.xml")"
  printf '%s\n' "${packaging:-jar}"
}

readonly revision="$(read_property revision)"
readonly junit_version="$(read_property junit.version)"
readonly spring_boot_version="$(read_property spring-boot.version)"
readonly archetype_plugin_version="$(read_property maven-archetype-plugin.version)"
readonly dependency_plugin_version="$(read_property maven-dependency-plugin.version)"
readonly temporary_root="$(mktemp -d)"
trap 'rm -rf "$temporary_root"' EXIT

fibra_java_executable=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  fibra_java_executable="$JAVA_HOME/bin/java"
else
  fibra_java_executable="$(command -v java || true)"
fi
[[ -n "$fibra_java_executable" ]] || {
  echo "分发验证需要 Java 可执行文件" >&2
  exit 1
}
readonly fibra_java_executable
readonly fibra_python_executable="$(command -v python3 || true)"
[[ -n "$fibra_python_executable" ]] || {
  echo "分发验证需要 Python 3 运行真实 PTY 门禁" >&2
  exit 1
}

readonly remote_repository="$temporary_root/remote"
readonly consumer_local_repository="$temporary_root/consumer-m2"
readonly consumer_project="$temporary_root/distribution"
readonly consumer_sources="$temporary_root/distribution-sources.tar"
mkdir -p "$remote_repository" "$consumer_local_repository"

cd "$repository_root"
"$maven_executable" --settings "$fixture/settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dfibra.repository.url="file://$remote_repository" \
  -pl "$module_list" -am clean deploy -DskipTests \
  -Darchetype.test.skip=true \
  -DaltDeploymentRepository="fibra-verification::file://$remote_repository"

"$maven_executable" --settings "$fixture/settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dfibra.repository.url="file://$remote_repository" \
  -Dfibra.distribution.verify.skip=false \
  -f "$repository_root/fibra-distribution/pom.xml" clean verify

readonly distribution_archive="$repository_root/fibra-distribution/target/fibra-$revision-bin.zip"
[[ -f "$distribution_archive" ]] || {
  echo "分发构建未生成发行 ZIP：$distribution_archive" >&2
  exit 1
}

expected_artifacts="$temporary_root/expected-artifacts"
actual_artifacts="$temporary_root/actual-artifacts"
for module in "${production_modules[@]}"; do
  basename "$module"
done | LC_ALL=C sort > "$expected_artifacts"
find "$remote_repository/com/sstlfsj" -mindepth 1 -maxdepth 1 -type d \
  -exec basename {} \; | LC_ALL=C sort > "$actual_artifacts"
cmp -s "$expected_artifacts" "$actual_artifacts" || {
  echo "临时发布仓库的 artifactId 集合与正式发布模块声明不一致" >&2
  diff -u "$expected_artifacts" "$actual_artifacts" >&2 || true
  exit 1
}

main_jar() {
  local artifact_id="$1"
  local directory="$remote_repository/com/sstlfsj/$artifact_id/$revision"
  local matches=()
  local candidate
  for candidate in "$directory/$artifact_id-"*.jar; do
    case "$candidate" in
      *-sources.jar|*-javadoc.jar) ;;
      *) matches+=("$candidate") ;;
    esac
  done
  [[ "${#matches[@]}" -eq 1 ]] || {
    echo "$artifact_id 应恰好有一个主 JAR" >&2
    exit 1
  }
  printf '%s\n' "${matches[0]}"
}

verify_main_jar_contents() {
  local artifact_id="$1"
  local jar_path
  local entries="$temporary_root/$artifact_id.entries"
  local extracted="$temporary_root/$artifact_id.extracted"
  jar_path="$(main_jar "$artifact_id")"
  jar tf "$jar_path" > "$entries"
  if rg -n -i --pcre2 "$forbidden_entry_pattern" "$entries"; then
    echo "$artifact_id 主 JAR 含禁止的发布内容" >&2
    exit 1
  fi
  mkdir "$extracted"
  (cd "$extracted" && jar xf "$jar_path")
  if rg -n -i --pcre2 --glob '!**/*.class' "$forbidden_text_pattern" "$extracted"; then
    echo "$artifact_id 主 JAR 的文本资源含旧模型或产品实现引用" >&2
    exit 1
  fi
}

main_pom() {
  local artifact_id="$1"
  local directory="$remote_repository/com/sstlfsj/$artifact_id/$revision"
  local matches=("$directory/$artifact_id-"*.pom)
  [[ "${#matches[@]}" -eq 1 ]] || {
    echo "$artifact_id 应恰好有一个发布 POM" >&2
    exit 1
  }
  printf '%s\n' "${matches[0]}"
}

for module in "${production_modules[@]}"; do
  artifact_id="$(basename "$module")"
  directory="$remote_repository/com/sstlfsj/$artifact_id/$revision"
  [[ -d "$directory" ]] || { echo "$module 未部署 $revision" >&2; exit 1; }
  poms=("$directory/$artifact_id-"*.pom)
  jars=("$directory/$artifact_id-"*.jar)
  sources=("$directory/$artifact_id-"*-sources.jar)
  javadocs=("$directory/$artifact_id-"*-javadoc.jar)
  [[ "${#poms[@]}" -eq 1 ]] || {
    echo "$module 应恰好发布一个 POM" >&2
    exit 1
  }
  if [[ "$(module_packaging "$module")" == pom ]]; then
    [[ "${#jars[@]}" -eq 0 ]] || {
      echo "$module 是纯 POM，不应发布 JAR" >&2
      exit 1
    }
  else
    [[ "${#jars[@]}" -eq 3 && "${#sources[@]}" -eq 1
        && "${#javadocs[@]}" -eq 1 ]] || {
      echo "$module 应恰好发布主 JAR、sources JAR 和 Javadoc JAR" >&2
      exit 1
    }
    verify_main_jar_contents "$artifact_id"
  fi
done

verify_generated_plugin() {
  local settings="$1"
  local generated="$2"
  local artifact_id="$3"
  mkdir -p "$generated"
  (
    cd "$generated"
    "$maven_executable" --settings "$settings" \
      --batch-mode --no-transfer-progress \
      -Dmaven.repo.local="$consumer_local_repository" \
      -Dfibra.repository.url="file://$remote_repository" \
      "org.apache.maven.plugins:maven-archetype-plugin:$archetype_plugin_version:generate" \
      -DarchetypeGroupId=com.sstlfsj \
      -DarchetypeArtifactId=fibra-plugin-archetype \
      -DarchetypeVersion="$revision" \
      -DgroupId=verification.generated -DartifactId="$artifact_id" \
      -Dversion=1.0.0 -Dpackage=verification.generated \
      -DpluginId="$artifact_id" -DfibraVersion="$revision" \
      -DinteractiveMode=false

    "$maven_executable" --settings "$settings" \
      --batch-mode --no-transfer-progress \
      -Dmaven.repo.local="$consumer_local_repository" \
      -Dfibra.repository.url="file://$remote_repository" \
      -f "$generated/$artifact_id/pom.xml" verify
  )
}

readonly fs_local_pom=("$remote_repository/com/sstlfsj/fibra-fs-local/$revision/fibra-fs-local-"*.pom)
readonly fs_local_dependency="$temporary_root/fibra-fs-local-jna.xml"
sed -n '/<artifactId>jna<\/artifactId>/,+3p' "${fs_local_pom[0]}" \
  > "$fs_local_dependency"
grep -q '<optional>true</optional>' "$fs_local_dependency"

readonly subprocess_local_pom=("$remote_repository/com/sstlfsj/fibra-subprocess-local/$revision/fibra-subprocess-local-"*.pom)
readonly subprocess_local_dependency="$temporary_root/fibra-subprocess-local-jna.xml"
sed -n '/<artifactId>jna<\/artifactId>/,+3p' "${subprocess_local_pom[0]}" \
  > "$subprocess_local_dependency"
grep -q '<optional>true</optional>' "$subprocess_local_dependency"

readonly search_pom=("$remote_repository/com/sstlfsj/fibra-tool-fs-search/$revision/fibra-tool-fs-search-"*.pom)
readonly search_dependency="$temporary_root/fibra-tool-fs-search-jackson.xml"
sed -n '/<artifactId>jackson-databind<\/artifactId>/,+3p' "${search_pom[0]}" \
  > "$search_dependency"
grep -q '<optional>true</optional>' "$search_dependency"

tar -C "$fixture" --exclude=target -cf "$consumer_sources" .
mkdir -p "$consumer_project"
tar -C "$consumer_project" -xf "$consumer_sources"
if find "$consumer_project" -type d -name target -print -quit | grep -q .; then
  echo "分发 fixture 不得复制既有 target" >&2
  exit 1
fi
if find "$consumer_project" -type l -print -quit | grep -q .; then
  echo "分发 fixture 不得包含符号链接" >&2
  exit 1
fi
if grep -R --line-number --fixed-strings "$repository_root" "$consumer_project"; then
  echo "分发 fixture 泄漏仓库绝对路径" >&2
  exit 1
fi

"$maven_executable" --settings "$consumer_project/settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$consumer_local_repository" \
  -Dfibra.repository.url="file://$remote_repository" \
  -Dfibra.version="$revision" -Djunit.version="$junit_version" \
  -Dspring-boot.version="$spring_boot_version" \
  -f "$consumer_project/pom.xml" clean verify

readonly cli_fixture_classpath_file="$temporary_root/cli-fixture.classpath"
"$maven_executable" --settings "$consumer_project/settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$consumer_local_repository" \
  -Dfibra.repository.url="file://$remote_repository" \
  -Dfibra.version="$revision" -Djunit.version="$junit_version" \
  -Dspring-boot.version="$spring_boot_version" \
  -f "$consumer_project/cli-application/pom.xml" \
  "org.apache.maven.plugins:maven-dependency-plugin:$dependency_plugin_version:build-classpath" \
  -Dmdep.outputFile="$cli_fixture_classpath_file" -DincludeScope=runtime

readonly cli_fixture_classpath="$(<"$cli_fixture_classpath_file")"
"$fibra_python_executable" "$consumer_project/verify-cli-tty.py" session \
  "$fibra_java_executable" \
  "$consumer_project/cli-application/target/classes:$cli_fixture_classpath" \
  "$temporary_root/cli-session-home"

for module in "${consumer_byte_compared_modules[@]}"; do
  artifact_id="$(basename "$module")"
  consumer_directory="$consumer_local_repository/com/sstlfsj/$artifact_id/$revision"
  consumer_jar="$consumer_directory/$artifact_id-$revision.jar"
  consumer_pom="$consumer_directory/$artifact_id-$revision.pom"
  consumer_tracking="$consumer_directory/_remote.repositories"
  [[ -f "$consumer_pom" ]] || {
    echo "仓外 Maven 临时仓库缺少 $artifact_id 的 POM" >&2
    exit 1
  }
  cmp -s "$(main_pom "$artifact_id")" "$consumer_pom" || {
    echo "$artifact_id POM 与临时发布制品字节不一致" >&2
    exit 1
  }
  [[ -f "$consumer_tracking" ]] || {
    echo "仓外 Maven 临时仓库缺少 $artifact_id 的远程来源记录" >&2
    exit 1
  }
  if [[ "$revision" == *-SNAPSHOT ]]; then
    tracked_version_pattern="${revision%-SNAPSHOT}-[0-9]{8}\\.[0-9]{6}-[0-9]+"
  else
    tracked_version_pattern="$revision"
  fi
  if [[ "$(module_packaging "$module")" != pom ]]; then
    [[ -f "$consumer_jar" ]] || {
      echo "仓外 Maven 临时仓库缺少 $artifact_id 的主 JAR" >&2
      exit 1
    }
    cmp -s "$(main_jar "$artifact_id")" "$consumer_jar" || {
      echo "$artifact_id 主 JAR 与临时发布制品字节不一致" >&2
      exit 1
    }
    grep -Eq "^$artifact_id-$tracked_version_pattern\\.jar>fibra-verification=$" \
      "$consumer_tracking" || {
      echo "$artifact_id 主 JAR 未从隔离的临时 file repository 解析" >&2
      exit 1
    }
  fi
  grep -Eq "^$artifact_id-$tracked_version_pattern\\.pom>fibra-verification=$" \
    "$consumer_tracking" || {
    echo "$artifact_id POM 未从隔离的临时 file repository 解析" >&2
    exit 1
  }
done

verify_generated_plugin "$consumer_project/settings.xml" \
  "$temporary_root/generated" generated-plugin

readonly external_cli_root="$temporary_root/external-cli"
readonly external_cli_extract="$external_cli_root/extracted"
readonly external_cli_install="$external_cli_extract/fibra-$revision"
readonly external_cli_launcher="$external_cli_install/bin/fibra"
readonly external_cli_plugin="$consumer_project/java-plugin/target/java-plugin-1.0.0.jar"
mkdir -p "$external_cli_extract"
unzip -q "$distribution_archive" -d "$external_cli_extract"
[[ -x "$external_cli_launcher" ]] || {
  echo "仓外 CLI 验证缺少 ZIP 启动器：$external_cli_launcher" >&2
  exit 1
}
[[ -f "$external_cli_plugin" ]] || {
  echo "仓外 CLI 验证缺少 Java 插件 JAR：$external_cli_plugin" >&2
  exit 1
}
mkdir -p "$external_cli_install/plugins/external/lib" \
  "$external_cli_install/config/profiles"
cp "$external_cli_plugin" "$external_cli_install/plugins/external/lib/main.jar"
printf '%s\n' \
  'format: 1' \
  'id: external' \
  'version: "1.0.0"' \
  'facets:' \
  '  - id: main' \
  '    role: host' \
  '    runtime: java' \
  '    target: host' \
  '    payload: lib/main.jar' \
  '    dependencies:' \
  '      - pluginId: fibra-storage' \
  '        facetId: main' \
  '    capabilities: []' \
  > "$external_cli_install/plugins/external/fibra-package.yaml"
printf '%s\n' \
  '- fibra-storage' \
  '- fibra-storage-json' \
  '- external' \
  > "$external_cli_install/config/profiles/default.packages.yaml"
printf '%s\n' \
  '- id: storage-provider' \
  '  plugin: {id: fibra-storage-json, facet: main, definition: storage-json}' \
  '  config:' \
  '    root: {$ref: /fibra/storageRoot}' \
  '  realm: {fibra.storage: shared}' \
  '- id: external-cli' \
  '  plugin: {id: external, facet: main, definition: external}' \
  '  realm: {fibra.storage: shared}' \
  > "$external_cli_install/config/profiles/default.yaml"

readonly external_cli_output="$external_cli_root/command.out"
readonly external_cli_error="$external_cli_root/command.err"
# JAVA_TOOL_OPTIONS 的 JVM 启动提示会污染应用 stderr，CLI 黑盒验收前移除它。
unset JAVA_TOOL_OPTIONS
"$external_cli_launcher" --home "$external_cli_install" external-cli echo \
  --prefix from- distribution > "$external_cli_output" 2> "$external_cli_error"
printf 'from-distribution\n' > "$external_cli_root/expected.out"
cmp -s "$external_cli_root/expected.out" "$external_cli_output" || {
  echo "仓外 Java 动态 CLI 命令输出不符合预期" >&2
  exit 1
}
[[ ! -s "$external_cli_error" ]] || {
  echo "仓外 Java 动态 CLI 命令写入 stderr" >&2
  cat "$external_cli_error" >&2
  exit 1
}

"$fibra_python_executable" "$consumer_project/verify-cli-tty.py" dynamic \
  "$external_cli_launcher" "$external_cli_install"

readonly external_cli_help="$external_cli_root/help.out"
readonly external_cli_help_error="$external_cli_root/help.err"
"$external_cli_launcher" --home "$external_cli_install" external-cli echo --help \
  > "$external_cli_help" 2> "$external_cli_help_error"
grep -F -- '输出仓外 Java 动态命令。' "$external_cli_help" >/dev/null || {
  echo "仓外 Java 动态 CLI help 缺少命令描述" >&2
  exit 1
}
grep -F -- '--prefix' "$external_cli_help" >/dev/null || {
  echo "仓外 Java 动态 CLI help 缺少选项" >&2
  exit 1
}
[[ ! -s "$external_cli_help_error" ]] || {
  echo "仓外 Java 动态 CLI help 写入 stderr" >&2
  cat "$external_cli_help_error" >&2
  exit 1
}

readonly external_cli_disable="$external_cli_root/disable.out"
readonly external_cli_disable_error="$external_cli_root/disable.err"
"$external_cli_launcher" --home "$external_cli_install" plugins disable external-cli \
  > "$external_cli_disable" 2> "$external_cli_disable_error"
[[ ! -s "$external_cli_disable_error" ]] || {
  echo "仓外 Java 动态 CLI 停用写入 stderr" >&2
  cat "$external_cli_disable_error" >&2
  exit 1
}

readonly external_cli_revoked_output="$external_cli_root/revoked.out"
readonly external_cli_revoked_error="$external_cli_root/revoked.err"
set +e
"$external_cli_launcher" --home "$external_cli_install" external-cli echo distribution \
  > "$external_cli_revoked_output" 2> "$external_cli_revoked_error"
external_cli_revoked_status=$?
set -e
[[ $external_cli_revoked_status -eq 2 ]] || {
  echo "停用后的仓外 Java 动态 CLI 命令退出码不是 2：$external_cli_revoked_status" >&2
  exit 1
}
[[ ! -s "$external_cli_revoked_output" ]] || {
  echo "停用后的仓外 Java 动态 CLI 命令仍产生 stdout" >&2
  exit 1
}
grep -F -- 'external-cli' "$external_cli_revoked_error" >/dev/null || {
  echo "停用后的仓外 Java 动态 CLI 命令缺少移除诊断" >&2
  exit 1
}

#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

readonly production_modules=(
  fibra-api
  fibra-core
  fibra-config
  fibra-artifact
  fibra-engine
  fibra-bridge
  fibra-runtime-java
  fibra-runtime-node
  fibra-registry
  fibra-cli-api
  fibra-cli
  fibra-spring
  fibra-spring-boot-starter
  fibra-plugin-archetype
  fibra-plugins/fibra-tool-api
  fibra-plugins/fibra-plugins-fs/fibra-fs
  fibra-plugins/fibra-plugins-fs/fibra-fs-local
  fibra-plugins/fibra-plugins-fs/fibra-tool-fs
  fibra-plugins/fibra-plugins-fs/fibra-tool-fs-search
  fibra-plugins/fibra-plugins-subprocess/fibra-subprocess
  fibra-plugins/fibra-plugins-subprocess/fibra-subprocess-local
  fibra-plugins/fibra-plugins-shell/fibra-shell
  fibra-plugins/fibra-plugins-shell/fibra-shell-local
  fibra-plugins/fibra-plugins-shell/fibra-tool-shell
  fibra-plugins/fibra-plugins-storage/fibra-storage
  fibra-plugins/fibra-plugins-storage/fibra-storage-json
  fibra-plugins/fibra-plugins-storage/fibra-tool-storage
)
readonly non_published_artifacts=(
  fibra
  fibra-plugins
  fibra-plugins-fs
  fibra-plugins-subprocess
  fibra-plugins-shell
  fibra-plugins-storage
  fibra-plugins-acceptance
  fibra-plugins-acceptance-host
  fibra-distribution
  fibra-example
  fibra-parity-tests
  fibra-benchmarks
)
readonly module_list="fibra-api,fibra-core,fibra-config,fibra-artifact,fibra-engine,fibra-bridge,fibra-runtime-java,fibra-runtime-node,fibra-registry,fibra-cli-api,fibra-cli,fibra-spring,fibra-spring-boot-starter,fibra-plugin-archetype,fibra-plugins/fibra-tool-api,fibra-plugins/fibra-plugins-fs/fibra-fs,fibra-plugins/fibra-plugins-fs/fibra-fs-local,fibra-plugins/fibra-plugins-fs/fibra-tool-fs,fibra-plugins/fibra-plugins-fs/fibra-tool-fs-search,fibra-plugins/fibra-plugins-subprocess/fibra-subprocess,fibra-plugins/fibra-plugins-subprocess/fibra-subprocess-local,fibra-plugins/fibra-plugins-shell/fibra-shell,fibra-plugins/fibra-plugins-shell/fibra-shell-local,fibra-plugins/fibra-plugins-shell/fibra-tool-shell,fibra-plugins/fibra-plugins-storage/fibra-storage,fibra-plugins/fibra-plugins-storage/fibra-storage-json,fibra-plugins/fibra-plugins-storage/fibra-tool-storage"
readonly maven_executable="${MVN:-mvn}"
readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly fixture="$repository_root/verification/distribution"

read_property() {
  local name="$1"
  sed -n "s:.*<$name>\([^<]*\)</$name>.*:\1:p" "$repository_root/pom.xml"
}

readonly revision="$(read_property revision)"
readonly junit_version="$(read_property junit.version)"
readonly spring_boot_version="$(read_property spring-boot.version)"
readonly archetype_plugin_version="$(read_property maven-archetype-plugin.version)"
readonly temporary_root="$(mktemp -d)"
trap 'rm -rf "$temporary_root"' EXIT

readonly remote_repository="$temporary_root/remote"
readonly build_repository="$temporary_root/build"
readonly distribution_repository="$temporary_root/distribution-repository"
readonly consumer_repository="$temporary_root/consumer"
readonly consumer_project="$temporary_root/distribution"
readonly consumer_sources="$temporary_root/distribution-sources.tar"
mkdir -p "$remote_repository" "$build_repository" \
  "$distribution_repository" "$consumer_repository"

cd "$repository_root"
"$maven_executable" --settings "$fixture/settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$build_repository" \
  -Dfibra.repository.url="file://$remote_repository" \
  -pl "$module_list" -am clean deploy -DskipTests \
  -Darchetype.test.skip=true \
  -DaltDeploymentRepository="fibra-verification::file://$remote_repository"

"$maven_executable" --settings "$fixture/settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$distribution_repository" \
  -Dfibra.repository.url="file://$remote_repository" \
  -f "$repository_root/fibra-distribution/pom.xml" clean verify

readonly distribution_archive="$repository_root/fibra-distribution/target/fibra-$revision-bin.zip"
[[ -f "$distribution_archive" ]] || {
  echo "空 Maven 仓构建未生成发行 ZIP：$distribution_archive" >&2
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
  echo "临时发布仓库的 artifactId 集合不是严格 27 个正式发布物" >&2
  diff -u "$expected_artifacts" "$actual_artifacts" >&2 || true
  exit 1
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
  [[ "${#jars[@]}" -eq 3 && "${#sources[@]}" -eq 1
      && "${#javadocs[@]}" -eq 1 ]] || {
    echo "$module 应恰好发布主 JAR、sources JAR 和 Javadoc JAR" >&2
    exit 1
  }
done

for artifact_id in "${non_published_artifacts[@]}"; do
  if [[ -d "$remote_repository/com/sstlfsj/$artifact_id" ]]; then
    echo "$artifact_id 不应部署到发布仓库" >&2
    exit 1
  fi
done

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

verify_generated_plugin() {
  local settings="$1"
  local generated="$2"
  local artifact_id="$3"
  mkdir -p "$generated"
  (
    cd "$generated"
    "$maven_executable" --settings "$settings" \
      --batch-mode --no-transfer-progress \
      -Dmaven.repo.local="$consumer_repository" \
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
      -Dmaven.repo.local="$consumer_repository" \
      -Dfibra.repository.url="file://$remote_repository" \
      -f "$generated/$artifact_id/pom.xml" verify
  )
}

readonly fs_local_jar="$(main_jar fibra-fs-local)"
readonly fs_local_pom=("$remote_repository/com/sstlfsj/fibra-fs-local/$revision/fibra-fs-local-"*.pom)
readonly fs_local_entries="$temporary_root/fibra-fs-local.entries"
readonly fs_local_notices="$temporary_root/fibra-fs-local.THIRD_PARTY_NOTICES.md"
readonly fs_local_dependency="$temporary_root/fibra-fs-local-jna.xml"
jar tf "$fs_local_jar" > "$fs_local_entries"
grep -qx 'META-INF/LICENSE' "$fs_local_entries"
grep -qx 'META-INF/THIRD_PARTY_NOTICES.md' "$fs_local_entries"
grep -qx 'com/sun/jna/Native.class' "$fs_local_entries"
grep -qx 'com/sun/jna/win32-x86/jnidispatch.dll' "$fs_local_entries"
grep -qx 'com/sun/jna/win32-x86-64/jnidispatch.dll' "$fs_local_entries"
grep -qx 'com/sun/jna/win32-aarch64/jnidispatch.dll' "$fs_local_entries"
unzip -p "$fs_local_jar" META-INF/THIRD_PARTY_NOTICES.md > "$fs_local_notices"
grep -q 'JNA' "$fs_local_notices"
grep -q 'Apache License 2.0' "$fs_local_notices"
sed -n '/<artifactId>jna<\/artifactId>/,+3p' "${fs_local_pom[0]}" \
  > "$fs_local_dependency"
grep -q '<optional>true</optional>' "$fs_local_dependency"

readonly subprocess_local_jar="$(main_jar fibra-subprocess-local)"
readonly subprocess_local_pom=("$remote_repository/com/sstlfsj/fibra-subprocess-local/$revision/fibra-subprocess-local-"*.pom)
readonly subprocess_local_entries="$temporary_root/fibra-subprocess-local.entries"
readonly subprocess_local_notices="$temporary_root/fibra-subprocess-local.THIRD_PARTY_NOTICES.md"
readonly subprocess_local_dependency="$temporary_root/fibra-subprocess-local-jna.xml"
jar tf "$subprocess_local_jar" > "$subprocess_local_entries"
grep -qx 'META-INF/LICENSE' "$subprocess_local_entries"
grep -qx 'META-INF/THIRD_PARTY_NOTICES.md' "$subprocess_local_entries"
grep -qx 'com/sun/jna/Native.class' "$subprocess_local_entries"
grep -qx 'com/sun/jna/win32-x86/jnidispatch.dll' "$subprocess_local_entries"
grep -qx 'com/sun/jna/win32-x86-64/jnidispatch.dll' "$subprocess_local_entries"
grep -qx 'com/sun/jna/win32-aarch64/jnidispatch.dll' "$subprocess_local_entries"
unzip -p "$subprocess_local_jar" META-INF/THIRD_PARTY_NOTICES.md \
  > "$subprocess_local_notices"
grep -q 'JNA' "$subprocess_local_notices"
grep -q 'Apache License 2.0' "$subprocess_local_notices"
sed -n '/<artifactId>jna<\/artifactId>/,+3p' "${subprocess_local_pom[0]}" \
  > "$subprocess_local_dependency"
grep -q '<optional>true</optional>' "$subprocess_local_dependency"

readonly search_jar="$(main_jar fibra-tool-fs-search)"
readonly search_pom=("$remote_repository/com/sstlfsj/fibra-tool-fs-search/$revision/fibra-tool-fs-search-"*.pom)
readonly search_entries="$temporary_root/fibra-tool-fs-search.entries"
readonly search_notice="$temporary_root/fibra-tool-fs-search.NOTICE"
readonly search_dependency="$temporary_root/fibra-tool-fs-search-jackson.xml"
jar tf "$search_jar" > "$search_entries"
grep -qx 'META-INF/LICENSE' "$search_entries"
grep -qx 'META-INF/THIRD_PARTY_NOTICES.md' "$search_entries"
grep -qx 'META-INF/FastDoubleParser-LICENSE' "$search_entries"
grep -qx 'META-INF/FastDoubleParser-ThirdParty-LICENSE' "$search_entries"
grep -qx 'META-INF/Schubfach-LICENSE' "$search_entries"
if grep -Eq '^(tools/jackson|com/fasterxml/jackson)/' "$search_entries"; then
  echo "fibra-tool-fs-search 包含未重定位的 Jackson class" >&2
  exit 1
fi
grep -q '^com/sstlfsj/fibra/plugins/fs/search/internal/jackson/' "$search_entries"
unzip -p "$search_jar" META-INF/NOTICE > "$search_notice"
grep -q 'Jackson JSON processor' "$search_notice"
grep -q 'FastDoubleParser' "$search_notice"
grep -q 'Schubfach' "$search_notice"
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
  -Dmaven.repo.local="$consumer_repository" \
  -Dfibra.repository.url="file://$remote_repository" \
  -Dfibra.version="$revision" -Djunit.version="$junit_version" \
  -Dspring-boot.version="$spring_boot_version" \
  -f "$consumer_project/pom.xml" clean verify

verify_generated_plugin "$consumer_project/settings.xml" \
  "$temporary_root/prewarm-generated" prewarmed-plugin

# 第一次构建从空本地仓库开始，正常解析外部依赖。随后只清除这个临时缓存中的
# Fibra 坐标，并把全部仓库镜像到本次部署仓库。第二次构建因而不能回退到
# Central 获取 Fibra，同时保留已由空仓构建证明可解析的外部依赖。
[[ "$consumer_repository" == "$temporary_root/consumer" ]] || {
  echo "拒绝清理非预期的消费者仓库：$consumer_repository" >&2
  exit 1
}
rm -rf "$consumer_repository/com/sstlfsj"
"$maven_executable" --settings "$consumer_project/isolated-settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$consumer_repository" \
  -Dfibra.repository.url="file://$remote_repository" \
  -Dfibra.version="$revision" -Djunit.version="$junit_version" \
  -Dspring-boot.version="$spring_boot_version" \
  -f "$consumer_project/pom.xml" clean verify

for module in "${production_modules[@]:14}"; do
  artifact_id="$(basename "$module")"
  consumer_directory="$consumer_repository/com/sstlfsj/$artifact_id/$revision"
  consumer_jar="$consumer_directory/$artifact_id-$revision.jar"
  consumer_pom="$consumer_directory/$artifact_id-$revision.pom"
  [[ -f "$consumer_jar" && -f "$consumer_pom" ]] || {
    echo "空仓消费者未完整解析 $artifact_id 的主 JAR 和 POM" >&2
    exit 1
  }
  cmp -s "$(main_jar "$artifact_id")" "$consumer_jar" || {
    echo "$artifact_id 主 JAR 与临时发布制品字节不一致" >&2
    exit 1
  }
  cmp -s "$(main_pom "$artifact_id")" "$consumer_pom" || {
    echo "$artifact_id POM 与临时发布制品字节不一致" >&2
    exit 1
  }
done

verify_generated_plugin "$consumer_project/isolated-settings.xml" \
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
  'formatVersion=1' \
  'runtime=java' \
  'payload=lib/main.jar' \
  > "$external_cli_install/plugins/external/plugin.properties"
printf '%s\n' \
  '- fibra-storage' \
  '- fibra-storage-json' \
  '- external' \
  > "$external_cli_install/config/profiles/default.artifacts.yaml"
printf '%s\n' \
  '- id: storage-provider' \
  '  plugin: storage-json' \
  '  config:' \
  '    root: {$ref: /fibra/storageRoot}' \
  '  realm: {fibra.storage: shared}' \
  '- id: external-cli' \
  '  plugin: external' \
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

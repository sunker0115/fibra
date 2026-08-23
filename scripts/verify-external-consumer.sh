#!/usr/bin/env bash
set -euo pipefail

readonly production_modules=(
  fibra-api
  fibra-core
  fibra-pf4j-api
  fibra-loader-pf4j
  fibra-loader-config
)
readonly module_list="fibra-api,fibra-core,fibra-pf4j-api,fibra-loader-pf4j,fibra-loader-config"
readonly maven_executable="${MVN:-mvn}"
readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly fixture_directory="$repository_root/verification/external-consumer"
readonly root_pom="$repository_root/pom.xml"

read_property() {
  local property_name="$1"
  local property_value
  property_value="$(sed -n "s:.*<$property_name>\([^<]*\)</$property_name>.*:\1:p" "$root_pom")"
  if [[ -z "$property_value" || "$property_value" == *$'\n'* ]]; then
    echo "根 POM 属性 $property_name 必须存在且只能定义一次" >&2
    exit 1
  fi
  printf '%s' "$property_value"
}

revision="$(read_property revision)"
pf4j_version="$(read_property pf4j.version)"
slf4j_version="$(read_property slf4j.version)"
maven_compiler_release="$(read_property maven.compiler.release)"
maven_compiler_plugin_version="$(read_property maven-compiler-plugin.version)"
maven_jar_plugin_version="$(read_property maven-jar-plugin.version)"
maven_resources_plugin_version="$(read_property maven-resources-plugin.version)"
maven_shade_plugin_version="$(read_property maven-shade-plugin.version)"
maven_surefire_plugin_version="$(read_property maven-surefire-plugin.version)"
readonly revision pf4j_version slf4j_version maven_compiler_release
readonly maven_compiler_plugin_version maven_jar_plugin_version
readonly maven_resources_plugin_version maven_shade_plugin_version
readonly maven_surefire_plugin_version

temporary_root="$(mktemp -d)"
readonly temporary_root
trap 'rm -rf "$temporary_root"' EXIT

readonly remote_repository="$temporary_root/remote-repository"
readonly producer_repository="$temporary_root/producer-repository"
readonly local_repository="$temporary_root/local-repository"
readonly consumer_worktree="$temporary_root/external-consumer"
readonly plugins_directory="$temporary_root/plugins"
mkdir -p "$remote_repository" "$producer_repository" "$local_repository" \
  "$consumer_worktree" "$plugins_directory"

if find "$fixture_directory" -type l -print -quit | grep -q .; then
  echo "外部消费方模板不得包含符号链接" >&2
  exit 1
fi
if find "$fixture_directory" -type d -name target -print -quit | grep -q .; then
  echo "外部消费方模板不得包含 target 构建残留" >&2
  exit 1
fi

cd "$repository_root"
"$maven_executable" --global-settings "$fixture_directory/settings.xml" \
  --settings "$fixture_directory/settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$producer_repository" \
  -pl "$module_list" -am clean deploy -DskipTests \
  -DaltDeploymentRepository="fibra-verification::file://$remote_repository"

actual_modules="$(
  find "$remote_repository/com/sstlfsj" -mindepth 1 -maxdepth 1 -type d \
    -exec basename {} \; | LC_ALL=C sort
)"
expected_modules="$(printf '%s\n' "${production_modules[@]}" | LC_ALL=C sort)"
if [[ "$actual_modules" != "$expected_modules" ]]; then
  echo "临时仓库中的 Fibra 模块不是约定的五个生产制品" >&2
  printf '实际模块：\n%s\n' "$actual_modules" >&2
  exit 1
fi

for module in "${production_modules[@]}"; do
  artifact_directory="$remote_repository/com/sstlfsj/$module/$revision"
  if [[ ! -d "$artifact_directory" ]]; then
    echo "$module 未部署版本 $revision" >&2
    exit 1
  fi

  main_jar_count=0
  sources_jar_count=0
  javadoc_jar_count=0
  pom_count=0
  for artifact in "$artifact_directory"/*; do
    [[ -f "$artifact" ]] || continue
    artifact_name="$(basename "$artifact")"
    case "$artifact_name" in
      *.sha1|*.md5) ;;
      *-sources.jar) sources_jar_count=$((sources_jar_count + 1)) ;;
      *-javadoc.jar) javadoc_jar_count=$((javadoc_jar_count + 1)) ;;
      *.jar) main_jar_count=$((main_jar_count + 1)) ;;
      *.pom) pom_count=$((pom_count + 1)) ;;
    esac
  done
  if [[ "$main_jar_count" -ne 1 || "$sources_jar_count" -ne 1 \
        || "$javadoc_jar_count" -ne 1 || "$pom_count" -ne 1 ]]; then
    echo "$module 的临时发布内容必须各含一个 POM、主 JAR、sources JAR 和 Javadoc JAR" >&2
    exit 1
  fi
done

cp -R "$fixture_directory"/. "$consumer_worktree"/

if grep -R --line-number --fixed-strings "$repository_root" "$consumer_worktree"; then
  echo "外部消费方模板泄漏了 Fibra 仓库绝对路径" >&2
  exit 1
fi
if grep -R --line-number -E 'fibra-[^/]+/target/classes|target/test-classes|<systemPath>' \
    "$consumer_worktree"; then
  echo "外部消费方模板引用了 Fibra 本地编译输出" >&2
  exit 1
fi
if find "$local_repository" -mindepth 1 -print -quit | grep -q .; then
  echo "外部消费验收的隔离 Maven 本地仓库必须从空目录开始" >&2
  exit 1
fi

"$maven_executable" --global-settings "$consumer_worktree/settings.xml" \
  --settings "$consumer_worktree/settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$local_repository" \
  -Dfibra.repository.url="file://$remote_repository" \
  -Dfibra.version="$revision" \
  -Dpf4j.version="$pf4j_version" \
  -Dslf4j.version="$slf4j_version" \
  -Dmaven.compiler.release="$maven_compiler_release" \
  -Dmaven-compiler-plugin.version="$maven_compiler_plugin_version" \
  -Dmaven-jar-plugin.version="$maven_jar_plugin_version" \
  -Dmaven-resources-plugin.version="$maven_resources_plugin_version" \
  -Dmaven-shade-plugin.version="$maven_shade_plugin_version" \
  -Dmaven-surefire-plugin.version="$maven_surefire_plugin_version" \
  -f "$consumer_worktree/pom.xml" package

for module in "${production_modules[@]}"; do
  local_artifact_directory="$local_repository/com/sstlfsj/$module/$revision"
  tracking_file="$local_artifact_directory/_remote.repositories"
  if [[ ! -f "$tracking_file" ]]; then
    echo "$module:$revision 缺少 Maven 仓库来源追踪文件" >&2
    exit 1
  fi

  local_main_jars=()
  local_poms=()
  for artifact in "$local_artifact_directory"/*; do
    [[ -f "$artifact" ]] || continue
    artifact_name="$(basename "$artifact")"
    case "$artifact_name" in
      *-sources.jar|*-javadoc.jar) ;;
      *.jar) local_main_jars+=("$artifact") ;;
      *.pom) local_poms+=("$artifact") ;;
    esac
  done
  if [[ "${#local_main_jars[@]}" -eq 0 || "${#local_poms[@]}" -eq 0 ]]; then
    echo "$module:$revision 在隔离本地仓库中缺少主 JAR 或 POM" >&2
    exit 1
  fi

  remote_artifact_directory="$remote_repository/com/sstlfsj/$module/$revision"
  remote_main_jar=""
  remote_pom=""
  remote_main_jar_count=0
  remote_pom_count=0
  for artifact in "$remote_artifact_directory"/*; do
    [[ -f "$artifact" ]] || continue
    artifact_name="$(basename "$artifact")"
    case "$artifact_name" in
      *-sources.jar|*-javadoc.jar) ;;
      *.jar)
        remote_main_jar="$artifact"
        remote_main_jar_count=$((remote_main_jar_count + 1))
        ;;
      *.pom)
        remote_pom="$artifact"
        remote_pom_count=$((remote_pom_count + 1))
        ;;
    esac
  done
  if [[ "$remote_main_jar_count" -ne 1 || "$remote_pom_count" -ne 1 ]]; then
    echo "$module:$revision 的临时远端主 JAR 或 POM 数量不正确" >&2
    exit 1
  fi

  tracked_main_jar=false
  for artifact in "${local_main_jars[@]}"; do
    if ! cmp -s "$artifact" "$remote_main_jar"; then
      echo "$module:$revision 的本地主 JAR 与临时远端制品不一致" >&2
      exit 1
    fi
    if grep -Fqx "$(basename "$artifact")>fibra-verification=" "$tracking_file"; then
      tracked_main_jar=true
    fi
  done

  tracked_pom=false
  for artifact in "${local_poms[@]}"; do
    if ! cmp -s "$artifact" "$remote_pom"; then
      echo "$module:$revision 的本地 POM 与临时远端制品不一致" >&2
      exit 1
    fi
    if grep -Fqx "$(basename "$artifact")>fibra-verification=" "$tracking_file"; then
      tracked_pom=true
    fi
  done
  if [[ "$tracked_main_jar" != true || "$tracked_pom" != true ]]; then
    echo "$module:$revision 的主 JAR 或 POM 缺少临时仓库来源记录" >&2
    exit 1
  fi
done

if [[ -n "${JAVA_HOME:-}" ]]; then
  java_executable="$JAVA_HOME/bin/java"
  jar_executable="$JAVA_HOME/bin/jar"
else
  java_executable="java"
  jar_executable="jar"
fi
readonly java_executable jar_executable

readonly core_jar="$consumer_worktree/core-app/target/external-core-app-all.jar"
readonly provider_jar="$consumer_worktree/provider-plugin/target/external-provider-plugin.jar"
readonly consumer_jar="$consumer_worktree/consumer-plugin/target/external-consumer-plugin.jar"
readonly host_jar="$consumer_worktree/host/target/external-host-all.jar"
for artifact in "$core_jar" "$provider_jar" "$consumer_jar" "$host_jar"; do
  if [[ ! -f "$artifact" ]]; then
    echo "外部消费方缺少可运行制品：$artifact" >&2
    exit 1
  fi
done

readonly provider_listing="$temporary_root/provider-plugin-contents.txt"
readonly consumer_listing="$temporary_root/consumer-plugin-contents.txt"
readonly host_listing="$temporary_root/host-contents.txt"
"$jar_executable" tf "$provider_jar" > "$provider_listing"
"$jar_executable" tf "$consumer_jar" > "$consumer_listing"
"$jar_executable" tf "$host_jar" > "$host_listing"

if ! grep -qx 'external/consumer/provider/ExternalProviderEntrypoint.class' \
    "$provider_listing" \
    || ! grep -qx 'external/consumer/provider/api/Greeting.class' \
    "$provider_listing"; then
  echo "provider 插件 JAR 缺少入口或服务契约" >&2
  exit 1
fi
if ! grep -qx 'external/consumer/plugin/ExternalConsumerEntrypoint.class' \
    "$consumer_listing"; then
  echo "consumer 插件 JAR 缺少入口" >&2
  exit 1
fi
if ! grep -qx 'META-INF/extensions.idx' "$provider_listing" \
    || ! grep -qx 'META-INF/extensions.idx' "$consumer_listing"; then
  echo "provider 或 consumer 插件 JAR 缺少 PF4J 扩展索引" >&2
  exit 1
fi
for listing in "$provider_listing" "$consumer_listing"; do
  if grep -Eq '^(com/sstlfsj/fibra/|org/pf4j/|org/reactivestreams/|reactor/|org/slf4j/)' \
      "$listing"; then
    echo "外部插件必须是瘦 JAR，不得内嵌 Fibra、PF4J、Reactor 或 SLF4J" >&2
    exit 1
  fi
done
if grep -qx 'external/consumer/provider/api/Greeting.class' "$consumer_listing"; then
  echo "consumer 插件不得复制 provider 拥有的服务契约" >&2
  exit 1
fi
if grep -Eq '^external/consumer/plugin/' "$provider_listing" \
    || grep -Eq '^external/consumer/provider/' "$consumer_listing"; then
  echo "provider 与 consumer 插件不得复制对方的实现或契约" >&2
  exit 1
fi
if grep -Eq '^external/consumer/(provider/|plugin/)' \
    "$host_listing"; then
  echo "外部宿主不得把 provider 或 consumer 的类放入自身 classpath" >&2
  exit 1
fi

readonly provider_manifest_directory="$temporary_root/provider-manifest"
readonly consumer_manifest_directory="$temporary_root/consumer-manifest"
mkdir -p "$provider_manifest_directory" "$consumer_manifest_directory"
(
  cd "$provider_manifest_directory"
  "$jar_executable" xf "$provider_jar" META-INF/MANIFEST.MF META-INF/extensions.idx
)
(
  cd "$consumer_manifest_directory"
  "$jar_executable" xf "$consumer_jar" META-INF/MANIFEST.MF META-INF/extensions.idx
)
readonly provider_manifest="$temporary_root/provider-manifest.txt"
readonly consumer_manifest="$temporary_root/consumer-manifest.txt"
tr -d '\r' < "$provider_manifest_directory/META-INF/MANIFEST.MF" > "$provider_manifest"
tr -d '\r' < "$consumer_manifest_directory/META-INF/MANIFEST.MF" > "$consumer_manifest"
if ! grep -Fqx 'Plugin-Id: external-provider-plugin' \
    "$provider_manifest" \
    || ! grep -Fqx 'Plugin-Version: 1.0.0' \
    "$provider_manifest" \
    || ! grep -Fqx 'Implementation-Version: 1.0.0' \
    "$provider_manifest"; then
  echo "provider 插件 Manifest 缺少固定的 PF4J 标识、版本或实现版本" >&2
  exit 1
fi
if grep -q '^Plugin-Dependencies:' "$provider_manifest"; then
  echo "provider 插件 Manifest 不得声明插件依赖" >&2
  exit 1
fi
if ! grep -Fqx 'Plugin-Id: external-consumer-plugin' \
    "$consumer_manifest" \
    || ! grep -Fqx 'Plugin-Version: 1.0.0' \
    "$consumer_manifest" \
    || ! grep -Fqx 'Implementation-Version: 1.0.0' \
    "$consumer_manifest" \
    || ! grep -Fqx 'Plugin-Dependencies: external-provider-plugin' \
    "$consumer_manifest"; then
  echo "consumer 插件 Manifest 缺少固定标识、版本、实现版本或 provider 依赖" >&2
  exit 1
fi

provider_extension_index="$provider_manifest_directory/META-INF/extensions.idx"
consumer_extension_index="$consumer_manifest_directory/META-INF/extensions.idx"
provider_extension_count="$(awk 'NF && $1 !~ /^#/ { count++ } END { print count + 0 }' \
  "$provider_extension_index")"
consumer_extension_count="$(awk 'NF && $1 !~ /^#/ { count++ } END { print count + 0 }' \
  "$consumer_extension_index")"
if [[ "$provider_extension_count" -ne 1 ]] \
    || ! grep -Fqx 'external.consumer.provider.ExternalProviderEntrypoint' \
    "$provider_extension_index"; then
  echo "provider 插件扩展索引必须只包含自己的唯一入口" >&2
  exit 1
fi
if [[ "$consumer_extension_count" -ne 1 ]] \
    || ! grep -Fqx 'external.consumer.plugin.ExternalConsumerEntrypoint' \
    "$consumer_extension_index"; then
  echo "consumer 插件扩展索引必须只包含自己的唯一入口" >&2
  exit 1
fi

(
  cd "$temporary_root"
  "$java_executable" -jar "$core_jar" 2>&1 | tee core-app.log
)
if ! grep -q 'EXTERNAL_CORE_CONSUMER_OK' "$temporary_root/core-app.log"; then
  echo "fibra-core 仓库外运行验收未输出成功标记" >&2
  exit 1
fi

cp "$provider_jar" "$plugins_directory/external-provider-plugin.jar"
cp "$consumer_jar" "$plugins_directory/external-consumer-plugin.jar"
readonly config_file="$temporary_root/fibra.yaml"
printf '%s\n' \
  '- id: consumer-one' \
  '  name: external-consumer-plugin' \
  '  inject: [external.consumer.provider.greeting]' \
  '  isolate:' \
  '    external.consumer.provider.greeting: one' \
  '    external.consumer.plugin.result: one' \
  '- id: provider-one' \
  '  name: external-provider-plugin' \
  '  isolate:' \
  '    external.consumer.provider.greeting: one' \
  '  config: provider-one' \
  '- id: consumer-two' \
  '  name: external-consumer-plugin' \
  '  inject: [external.consumer.provider.greeting]' \
  '  isolate:' \
  '    external.consumer.provider.greeting: two' \
  '    external.consumer.plugin.result: two' \
  '- id: provider-two' \
  '  name: external-provider-plugin' \
  '  isolate:' \
  '    external.consumer.provider.greeting: two' \
  '  config: provider-two' > "$config_file"
(
  cd "$temporary_root"
  "$java_executable" -jar "$host_jar" "$plugins_directory" "$config_file" 2>&1 | tee host.log
)
if ! grep -q 'EXTERNAL_CONFIG_LOADER_CONSUMER_OK' "$temporary_root/host.log"; then
  echo "fibra-loader-config 仓库外配置事务验收未输出成功标记" >&2
  exit 1
fi

echo "仓库外消费验收通过：$revision"

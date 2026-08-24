#!/usr/bin/env bash
set -euo pipefail

readonly production_modules=(
  fibra-api
  fibra-core
  fibra-pf4j-api
  fibra-loader-pf4j
  fibra-loader-config
  fibra-engine
  fibra-spring
  fibra-spring-boot-autoconfigure
  fibra-spring-boot-starter
  fibra-plugin-archetype
)
readonly consumer_modules=(
  fibra-api
  fibra-core
  fibra-pf4j-api
  fibra-loader-pf4j
  fibra-loader-config
  fibra-engine
)
readonly module_list="fibra-api,fibra-core,fibra-pf4j-api,fibra-loader-pf4j,fibra-loader-config,fibra-engine,fibra-spring,fibra-spring-boot-autoconfigure,fibra-spring-boot-starter,fibra-plugin-archetype"
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
maven_assembly_plugin_version="$(read_property maven-assembly-plugin.version)"
maven_compiler_plugin_version="$(read_property maven-compiler-plugin.version)"
maven_jar_plugin_version="$(read_property maven-jar-plugin.version)"
maven_resources_plugin_version="$(read_property maven-resources-plugin.version)"
maven_shade_plugin_version="$(read_property maven-shade-plugin.version)"
maven_surefire_plugin_version="$(read_property maven-surefire-plugin.version)"
readonly revision pf4j_version slf4j_version maven_compiler_release
readonly maven_assembly_plugin_version maven_compiler_plugin_version maven_jar_plugin_version
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
  -Darchetype.test.skip=true \
  -DaltDeploymentRepository="fibra-verification::file://$remote_repository"

actual_modules="$(
  find "$remote_repository/com/sstlfsj" -mindepth 1 -maxdepth 1 -type d \
    -exec basename {} \; | LC_ALL=C sort
)"
expected_modules="$(printf '%s\n' "${production_modules[@]}" | LC_ALL=C sort)"
if [[ "$actual_modules" != "$expected_modules" ]]; then
  echo "临时仓库中的 Fibra 模块不是约定的生产制品集合" >&2
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
  -Dmaven-assembly-plugin.version="$maven_assembly_plugin_version" \
  -Dmaven-compiler-plugin.version="$maven_compiler_plugin_version" \
  -Dmaven-jar-plugin.version="$maven_jar_plugin_version" \
  -Dmaven-resources-plugin.version="$maven_resources_plugin_version" \
  -Dmaven-shade-plugin.version="$maven_shade_plugin_version" \
  -Dmaven-surefire-plugin.version="$maven_surefire_plugin_version" \
  -f "$consumer_worktree/pom.xml" package

for module in "${consumer_modules[@]}"; do
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
readonly contract_v1_zip="$consumer_worktree/contract-plugin/target/external-contract-plugin-1.0.0.zip"
readonly provider_v1_zip="$consumer_worktree/provider-plugin/target/external-provider-plugin-1.0.0.zip"
readonly consumer_v1_zip="$consumer_worktree/consumer-plugin/target/external-consumer-plugin-1.0.0.zip"
readonly contract_v2_zip="$consumer_worktree/contract-plugin/target/external-contract-plugin-2.0.0.zip"
readonly provider_v2_zip="$consumer_worktree/provider-plugin/target/external-provider-plugin-2.0.0.zip"
readonly consumer_v2_zip="$consumer_worktree/consumer-plugin/target/external-consumer-plugin-2.0.0.zip"
readonly host_jar="$consumer_worktree/host/target/external-host-all.jar"
for artifact in "$core_jar" "$contract_v1_zip" "$provider_v1_zip" \
    "$consumer_v1_zip" "$contract_v2_zip" "$provider_v2_zip" \
    "$consumer_v2_zip" "$host_jar"; do
  if [[ ! -f "$artifact" ]]; then
    echo "外部消费方缺少构建制品：$artifact" >&2
    exit 1
  fi
done

inspect_package() {
  local package_zip="$1"
  local plugin_id="$2"
  local plugin_version="$3"
  local package_kind="$4"
  local inspection_directory="$temporary_root/inspect-$plugin_id-$plugin_version"
  local package_listing="$inspection_directory/package.txt"
  local main_listing="$inspection_directory/main.txt"
  local package_root="$inspection_directory/$plugin_id"
  local main_jar="$package_root/lib/$plugin_id-$plugin_version.jar"
  mkdir -p "$inspection_directory"
  "$jar_executable" tf "$package_zip" > "$package_listing"
  if grep -Ev "^${plugin_id}/" "$package_listing" | grep -q .; then
    echo "$package_zip 必须只有顶层目录 $plugin_id" >&2
    exit 1
  fi
  (
    cd "$inspection_directory"
    "$jar_executable" xf "$package_zip"
  )
  if [[ ! -f "$package_root/plugin.properties" || ! -f "$main_jar" ]]; then
    echo "$package_zip 缺少标准 plugin.properties 或固定命名主 JAR" >&2
    exit 1
  fi
  if ! grep -Fqx "plugin.id=$plugin_id" "$package_root/plugin.properties" \
      || ! grep -Fqx "plugin.version=$plugin_version" "$package_root/plugin.properties"; then
    echo "$package_zip 的描述符 ID 或版本与包名不一致" >&2
    exit 1
  fi
  if grep -q '^plugin.class=' "$package_root/plugin.properties"; then
    echo "$package_zip 不得声明 PF4J Plugin-Class" >&2
    exit 1
  fi
  "$jar_executable" tf "$main_jar" > "$main_listing"
  if grep -Eq '^(com/sstlfsj/fibra/|org/pf4j/|org/reactivestreams/|reactor/|org/slf4j/)' \
      "$main_listing"; then
    echo "$package_zip 的主 JAR 不得内嵌宿主共享运行时" >&2
    exit 1
  fi
  if [[ "$package_kind" == contract ]]; then
    if ! grep -qx 'external/consumer/contract/Greeting.class' "$main_listing" \
        || grep -qx 'META-INF/extensions.idx' "$main_listing"; then
      echo "$package_zip 必须是只拥有 Greeting 且无入口索引的 contract-only 包" >&2
      exit 1
    fi
  else
    if grep -qx 'external/consumer/contract/Greeting.class' "$main_listing" \
        || ! grep -qx 'META-INF/extensions.idx' "$main_listing"; then
      echo "$package_zip 不得复制 contract，且必须包含自身入口索引" >&2
      exit 1
    fi
  fi
}

inspect_package "$contract_v1_zip" external-contract-plugin 1.0.0 contract
inspect_package "$provider_v1_zip" external-provider-plugin 1.0.0 executable
inspect_package "$consumer_v1_zip" external-consumer-plugin 1.0.0 executable
inspect_package "$contract_v2_zip" external-contract-plugin 2.0.0 contract
inspect_package "$provider_v2_zip" external-provider-plugin 2.0.0 executable
inspect_package "$consumer_v2_zip" external-consumer-plugin 2.0.0 executable

readonly provider_root="$temporary_root/inspect-external-provider-plugin-1.0.0/external-provider-plugin"
readonly consumer_root="$temporary_root/inspect-external-consumer-plugin-1.0.0/external-consumer-plugin"
if [[ "$(find "$provider_root/lib" -maxdepth 1 -name 'commons-text-*.jar' | wc -l | tr -d ' ')" -ne 1 ]]; then
  echo "provider 标准包必须携带且只携带一份 Commons Text 私有依赖" >&2
  exit 1
fi
if find "$consumer_root/lib" -maxdepth 1 -name 'commons-text-*.jar' -print -quit | grep -q .; then
  echo "consumer 标准包不得复制 provider 私有依赖" >&2
  exit 1
fi
if ! grep -Fqx 'plugin.dependencies=external-contract-plugin@>=1.0.0 & <2.0.0' \
    "$provider_root/plugin.properties" \
    || ! grep -Fqx 'plugin.dependencies=external-contract-plugin@>=1.0.0 & <2.0.0' \
    "$consumer_root/plugin.properties"; then
  echo "provider 和 consumer v1 必须只声明 contract 二进制依赖范围" >&2
  exit 1
fi

readonly host_listing="$temporary_root/host-contents.txt"
"$jar_executable" tf "$host_jar" > "$host_listing"
if grep -Eq '^external/consumer/(contract/|provider/|plugin/)' "$host_listing"; then
  echo "外部宿主不得把 contract、provider 或 consumer 类放入自身 classpath" >&2
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

readonly config_file="$temporary_root/fibra.yaml"
readonly deployment_root="$temporary_root/deployment-v2"
readonly deployment_zip="$temporary_root/external-deployment-2.0.0.zip"
for package_zip in "$contract_v1_zip" "$provider_v1_zip" "$consumer_v1_zip"; do
  (
    cd "$plugins_directory"
    "$jar_executable" xf "$package_zip"
  )
done
cp "$consumer_worktree/host/config/fibra.yaml" "$config_file"
mkdir -p "$deployment_root/config" "$deployment_root/plugins"
cp "$config_file" "$deployment_root/config/fibra.yaml"
cp "$consumer_v2_zip" "$deployment_root/plugins/$(basename "$consumer_v2_zip")"
cp "$contract_v2_zip" "$deployment_root/plugins/$(basename "$contract_v2_zip")"
cp "$provider_v2_zip" "$deployment_root/plugins/$(basename "$provider_v2_zip")"
printf '%s\n' \
  'deployment.id=external-consumer' \
  'deployment.version=2.0.0' \
  'config.path=config/fibra.yaml' \
  'plugin.0=plugins/external-consumer-plugin-2.0.0.zip' \
  'plugin.1=plugins/external-contract-plugin-2.0.0.zip' \
  'plugin.2=plugins/external-provider-plugin-2.0.0.zip' \
  > "$deployment_root/deployment.properties"
readonly deployment_files=(
  config/fibra.yaml
  deployment.properties
  plugins/external-consumer-plugin-2.0.0.zip
  plugins/external-contract-plugin-2.0.0.zip
  plugins/external-provider-plugin-2.0.0.zip
)
for deployment_file in "${deployment_files[@]}"; do
  deployment_digest="$(shasum -a 256 "$deployment_root/$deployment_file" | awk '{print $1}')"
  printf '%s  %s\n' "$deployment_digest" "$deployment_file" \
    >> "$deployment_root/checksums.sha256"
done
"$jar_executable" --create --file "$deployment_zip" --no-manifest \
  -C "$deployment_root" config/fibra.yaml \
  -C "$deployment_root" deployment.properties \
  -C "$deployment_root" plugins/external-consumer-plugin-2.0.0.zip \
  -C "$deployment_root" plugins/external-contract-plugin-2.0.0.zip \
  -C "$deployment_root" plugins/external-provider-plugin-2.0.0.zip \
  -C "$deployment_root" checksums.sha256
(
  cd "$temporary_root"
  "$java_executable" -jar "$host_jar" "$plugins_directory" "$config_file" \
    "$deployment_zip" 2>&1 | tee host.log
)
if ! grep -q 'EXTERNAL_ENGINE_CONSUMER_OK' "$temporary_root/host.log"; then
  echo "fibra-engine 仓库外联合部署验收未输出成功标记" >&2
  exit 1
fi

echo "仓库外消费验收通过：$revision"

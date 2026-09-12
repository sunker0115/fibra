#!/usr/bin/env bash
set -euo pipefail

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
readonly module_list="fibra-api,fibra-core,fibra-config,fibra-artifact,fibra-engine,fibra-bridge,fibra-runtime-java,fibra-runtime-node,fibra-registry,fibra-cli,fibra-spring,fibra-spring-boot-starter,fibra-plugin-archetype,fibra-plugins/fibra-tool-api,fibra-plugins/fibra-plugins-fs/fibra-fs,fibra-plugins/fibra-plugins-fs/fibra-fs-local,fibra-plugins/fibra-plugins-fs/fibra-tool-fs,fibra-plugins/fibra-plugins-fs/fibra-tool-fs-search,fibra-plugins/fibra-plugins-subprocess/fibra-subprocess,fibra-plugins/fibra-plugins-subprocess/fibra-subprocess-local,fibra-plugins/fibra-plugins-shell/fibra-shell,fibra-plugins/fibra-plugins-shell/fibra-shell-local,fibra-plugins/fibra-plugins-shell/fibra-tool-shell,fibra-plugins/fibra-plugins-storage/fibra-storage,fibra-plugins/fibra-plugins-storage/fibra-storage-json,fibra-plugins/fibra-plugins-storage/fibra-tool-storage"
readonly maven_executable="${MVN:-mvn}"
snapshot_directory="$(mktemp -d)"
trap 'rm -rf "$snapshot_directory"' EXIT

for module in "${production_modules[@]}"; do
  artifact_id="$(basename "$module")"
  mkdir -p "$snapshot_directory/$artifact_id"
  cp "$module/.flattened-pom.xml" "$snapshot_directory/$artifact_id/.flattened-pom.xml"

  artifact_count=0
  for artifact in "$module"/target/"$artifact_id"-*.jar; do
    if [[ -f "$artifact" ]]; then
      cp "$artifact" "$snapshot_directory/$artifact_id/$(basename "$artifact")"
      artifact_count=$((artifact_count + 1))
    fi
  done
  if [[ "$artifact_count" -ne 3 ]]; then
    echo "$module 应恰好生成主 JAR、sources JAR 和 Javadoc JAR" >&2
    exit 1
  fi
done

compare_release() {
  for module in "${production_modules[@]}"; do
    artifact_id="$(basename "$module")"
    cmp "$snapshot_directory/$artifact_id/.flattened-pom.xml" "$module/.flattened-pom.xml"
    for expected in "$snapshot_directory/$artifact_id"/*.jar; do
      cmp "$expected" "$module/target/$(basename "$expected")"
    done
  done
}

"$maven_executable" --batch-mode --no-transfer-progress \
  -pl "$module_list" -am clean package -DskipTests
compare_release

# 再次从已有主 JAR 打包，防止 Shade 等插件把上一次处理后的制品当作输入。
"$maven_executable" --batch-mode --no-transfer-progress \
  -pl "$module_list" -am package -DskipTests
compare_release

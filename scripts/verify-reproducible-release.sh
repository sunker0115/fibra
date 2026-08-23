#!/usr/bin/env bash
set -euo pipefail

readonly production_modules=(
  fibra-api
  fibra-core
  fibra-pf4j-api
  fibra-loader-pf4j
  fibra-loader-config
  fibra-spring-boot-starter
)
readonly module_list="fibra-api,fibra-core,fibra-pf4j-api,fibra-loader-pf4j,fibra-loader-config,fibra-spring-boot-starter"
readonly maven_executable="${MVN:-mvn}"
snapshot_directory="$(mktemp -d)"
trap 'rm -rf "$snapshot_directory"' EXIT

for module in "${production_modules[@]}"; do
  mkdir -p "$snapshot_directory/$module"
  cp "$module/.flattened-pom.xml" "$snapshot_directory/$module/.flattened-pom.xml"

  artifact_count=0
  for artifact in "$module"/target/"$module"-*.jar; do
    if [[ -f "$artifact" ]]; then
      cp "$artifact" "$snapshot_directory/$module/$(basename "$artifact")"
      artifact_count=$((artifact_count + 1))
    fi
  done
  if [[ "$artifact_count" -ne 3 ]]; then
    echo "$module 应恰好生成主 JAR、sources JAR 和 Javadoc JAR" >&2
    exit 1
  fi
done

"$maven_executable" --batch-mode --no-transfer-progress \
  -pl "$module_list" -am clean package -DskipTests

for module in "${production_modules[@]}"; do
  cmp "$snapshot_directory/$module/.flattened-pom.xml" "$module/.flattened-pom.xml"
  for expected in "$snapshot_directory/$module"/*.jar; do
    cmp "$expected" "$module/target/$(basename "$expected")"
  done
done

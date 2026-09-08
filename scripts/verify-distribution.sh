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
  fibra-spring
  fibra-spring-boot-starter
  fibra-plugin-archetype
)
readonly module_list="fibra-api,fibra-core,fibra-config,fibra-artifact,fibra-engine,fibra-bridge,fibra-runtime-java,fibra-runtime-node,fibra-registry,fibra-spring,fibra-spring-boot-starter,fibra-plugin-archetype"
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
readonly consumer_repository="$temporary_root/consumer"
readonly consumer_project="$temporary_root/distribution"
mkdir -p "$remote_repository" "$consumer_repository"

cd "$repository_root"
"$maven_executable" --batch-mode --no-transfer-progress \
  -pl "$module_list" -am clean deploy -DskipTests \
  -Darchetype.test.skip=true \
  -DaltDeploymentRepository="fibra-verification::file://$remote_repository"

for module in "${production_modules[@]}"; do
  directory="$remote_repository/com/sstlfsj/$module/$revision"
  [[ -d "$directory" ]] || { echo "$module 未部署 $revision" >&2; exit 1; }
  poms=("$directory/$module-"*.pom)
  jars=("$directory/$module-"*.jar)
  sources=("$directory/$module-"*-sources.jar)
  javadocs=("$directory/$module-"*-javadoc.jar)
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

cp -R "$fixture" "$consumer_project"
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
  -f "$consumer_project/pom.xml" verify

readonly generated="$temporary_root/generated"
mkdir -p "$generated"
cd "$generated"
"$maven_executable" --settings "$consumer_project/settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$consumer_repository" \
  -Dfibra.repository.url="file://$remote_repository" \
  "org.apache.maven.plugins:maven-archetype-plugin:$archetype_plugin_version:generate" \
  -DarchetypeGroupId=com.sstlfsj \
  -DarchetypeArtifactId=fibra-plugin-archetype \
  -DarchetypeVersion="$revision" \
  -DgroupId=verification.generated -DartifactId=generated-plugin \
  -Dversion=1.0.0 -Dpackage=verification.generated \
  -DpluginId=generated-plugin -DfibraVersion="$revision" \
  -DinteractiveMode=false

"$maven_executable" --settings "$consumer_project/settings.xml" \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$consumer_repository" \
  -Dfibra.repository.url="file://$remote_repository" \
  -f "$generated/generated-plugin/pom.xml" verify

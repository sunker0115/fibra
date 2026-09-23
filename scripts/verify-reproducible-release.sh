#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

readonly maven_executable="${MVN:-mvn}"
readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
production_modules=()
while IFS= read -r module; do
  production_modules+=("$module")
done < <("$repository_root/scripts/release-maven-modules.sh")
readonly -a production_modules
readonly module_list="$("$repository_root/scripts/release-maven-modules.sh" csv),fibra-distribution"
cd "$repository_root"
readonly revision="$(sed -n 's:.*<revision>\([^<]*\)</revision>.*:\1:p' pom.xml)"
readonly distribution_archive="fibra-distribution/target/fibra-$revision-bin.zip"
snapshot_directory="$(mktemp -d)"
trap 'rm -rf "$snapshot_directory"' EXIT

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

file_mode() {
  if [[ "$(uname -s)" == Darwin ]]; then
    stat -f '%Lp' "$1"
  else
    stat -c '%a' "$1"
  fi
}

module_packaging() {
  local packaging
  packaging="$(sed -n 's:.*<packaging>\([^<]*\)</packaging>.*:\1:p' "$1/pom.xml")"
  printf '%s\n' "${packaging:-jar}"
}

distribution_manifest() {
  local output=$1
  (
    cd "fibra-distribution/target/fibra-$revision"
    while IFS= read -r path; do
      if [[ -d "$path" ]]; then
        printf 'directory\t%s\t-\t%s\n' "$(file_mode "$path")" "$path"
      elif [[ -f "$path" ]]; then
        printf 'file\t%s\t%s\t%s\n' \
          "$(file_mode "$path")" "$(sha256_file "$path")" "$path"
      else
        echo "发行目录包含不支持的条目：$path" >&2
        exit 1
      fi
    done < <(find . -mindepth 1 -print | LC_ALL=C sort)
  ) > "$output"
}

"$maven_executable" --batch-mode --no-transfer-progress \
  -pl "$module_list" -am clean package -DskipTests

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
  expected_artifact_count=3
  expected_artifact_description="主 JAR、sources JAR 和 Javadoc JAR"
  if [[ "$(module_packaging "$module")" == pom ]]; then
    expected_artifact_count=0
    expected_artifact_description="纯 POM，不生成 JAR"
  fi
  if [[ "$artifact_count" -ne "$expected_artifact_count" ]]; then
    echo "$module 应生成$expected_artifact_description" >&2
    exit 1
  fi
done
[[ -f "$distribution_archive" ]] || {
  echo "缺少发行 ZIP：$distribution_archive" >&2
  exit 1
}
cp "$distribution_archive" "$snapshot_directory/fibra-bin.zip"
distribution_manifest "$snapshot_directory/fibra-tree.manifest"

compare_release() {
  for module in "${production_modules[@]}"; do
    artifact_id="$(basename "$module")"
    cmp "$snapshot_directory/$artifact_id/.flattened-pom.xml" "$module/.flattened-pom.xml"
    for expected in "$snapshot_directory/$artifact_id"/*.jar; do
      cmp "$expected" "$module/target/$(basename "$expected")"
    done
  done
  cmp "$snapshot_directory/fibra-bin.zip" "$distribution_archive"
  distribution_manifest "$snapshot_directory/current-fibra-tree.manifest"
  cmp "$snapshot_directory/fibra-tree.manifest" \
    "$snapshot_directory/current-fibra-tree.manifest"
}

"$maven_executable" --batch-mode --no-transfer-progress \
  -pl "$module_list" -am clean package -DskipTests
compare_release

# 再次从已有主 JAR 打包，防止 Shade 等插件把上一次处理后的制品当作输入。
"$maven_executable" --batch-mode --no-transfer-progress \
  -pl "$module_list" -am package -DskipTests
compare_release

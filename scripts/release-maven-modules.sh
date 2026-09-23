#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly expected_release_module_count=29

release_modules() {
  find "$repository_root" -name pom.xml -not -path '*/target/*' -print \
    | LC_ALL=C sort \
    | while IFS= read -r pom; do
        if grep -Fq '<maven.deploy.skip>false</maven.deploy.skip>' "$pom"; then
          local module="${pom%/pom.xml}"
          printf '%s\n' "${module#"$repository_root"/}"
        fi
      done
}

modules=()
while IFS= read -r module; do
  modules+=("$module")
done < <(release_modules)

if (( ${#modules[@]} != expected_release_module_count )); then
  echo "正式 Maven 发布模块数量应为 $expected_release_module_count，实际为 ${#modules[@]}" >&2
  exit 1
fi

case "${1:-lines}" in
  lines)
    printf '%s\n' "${modules[@]}"
    ;;
  csv)
    (IFS=,; echo "${modules[*]}")
    ;;
  *)
    echo "用法：$0 [lines|csv]" >&2
    exit 2
    ;;
esac

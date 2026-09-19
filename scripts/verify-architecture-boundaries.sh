#!/usr/bin/env bash
set -euo pipefail

readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly forbidden_path_pattern='(^|/)(fibra-runtime-host|fibra-runtime-client|client-runtime|client-runtime-web|client-react)(/|$)|(^|/)(plugin[.]properties|[^/]+[.]artifacts[.]yaml)$'
readonly forbidden_source_pattern='PluginRuntimeAdapter|JavaPluginRuntimeAdapter|NodePluginRuntimeAdapter|ArtifactRuntime|ExecutionRuntime|ReplaceConfigContext|ArtifactPackage|PluginArtifactProbe|fibra-runtime-host|fibra-runtime-client|client-runtime-web|client-runtime|client-react'

cd "$repository_root"

tracked_paths="$(rg --files | LC_ALL=C sort)"
if printf '%s\n' "$tracked_paths" | rg -n "$forbidden_path_pattern"; then
  echo "架构边界失败：仓库仍包含已删除的 runtime、client 实现或旧清单路径" >&2
  exit 1
fi

production_files=()
while IFS= read -r path; do
  case "$path" in
    pom.xml|fibra-*/pom.xml|fibra-*/src/main/*|client/packages/*/package.json|client/packages/*/src/*)
      production_files+=("$path")
      ;;
  esac
done <<< "$tracked_paths"

if rg -n "$forbidden_source_pattern" "${production_files[@]}"; then
  echo "架构边界失败：生产源码或正式 POM 重新引用已删除模型" >&2
  exit 1
fi

echo "架构边界通过：正式源码、模块与清单只保留当前 RuntimeDriver/client API 模型"

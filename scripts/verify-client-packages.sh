#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd -P)
client_dir="$repo_root/client"
consumer_source="$client_dir/tests/package-consumer"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/fibra-client-packages.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT

fail() {
  echo "verify-client-packages: $*" >&2
  exit 1
}

root_version=$(sed -n 's|^[[:space:]]*<revision>\([^<]*\)</revision>[[:space:]]*$|\1|p' "$repo_root/pom.xml")
[[ -n "$root_version" ]] || fail "cannot read Maven revision"

package_version() {
  node -e 'process.stdout.write(JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8")).version)' "$1/package.json"
}

for package_dir in "$client_dir/packages/client-api" "$client_dir/packages/client-protocol"; do
  [[ "$(package_version "$package_dir")" == "$root_version" ]] || fail "$(basename "$package_dir") version must match Maven revision $root_version"
done

env CI=true pnpm --config.update-notifier=false --dir "$client_dir" run test
env CI=true pnpm --config.update-notifier=false --dir "$client_dir" run lint:boundaries

cmp -- "$client_dir/packages/client-api/dist/index.d.ts" "$client_dir/api-baseline/client-api.d.ts" \
  || fail "client API declaration baseline drifted"
cmp -- "$client_dir/packages/client-protocol/dist/index.d.ts" "$client_dir/api-baseline/client-protocol.d.ts" \
  || fail "client protocol declaration baseline drifted"

pack_dir="${FIBRA_NPM_PACK_DIR:-$tmp_dir/packs}"
mkdir -p "$pack_dir"
if find "$pack_dir" -maxdepth 1 -type f -name '*.tgz' -print -quit | grep -q .; then
  fail "npm pack 输出目录必须为空：$pack_dir"
fi
env CI=true pnpm --config.update-notifier=false --dir "$client_dir" --filter @sstlfsj/fibra-client-api pack --pack-destination "$pack_dir"
env CI=true pnpm --config.update-notifier=false --dir "$client_dir" --filter @sstlfsj/fibra-client-protocol pack --pack-destination "$pack_dir"

api_tarball=$(find "$pack_dir" -type f -name 'sstlfsj-fibra-client-api-*.tgz' -print -quit)
protocol_tarball=$(find "$pack_dir" -type f -name 'sstlfsj-fibra-client-protocol-*.tgz' -print -quit)
[[ -n "$api_tarball" ]] || fail "client API tarball was not created"
[[ -n "$protocol_tarball" ]] || fail "client protocol tarball was not created"

verify_tarball() {
  local tarball=$1
  local expected_name=$2
  local package_dir=$3
  local unpack_dir="$tmp_dir/$(basename "$tarball" .tgz)"
  local members="$unpack_dir.members"

  mkdir "$unpack_dir"
  tar -tzf "$tarball" > "$members"
  tar -xzf "$tarball" -C "$unpack_dir"
  while IFS= read -r member; do
    case "$member" in
      package/package.json|package/LICENSE|package/NOTICE|package/dist/*) ;;
      *) fail "$(basename "$tarball") contains forbidden entry $member" ;;
    esac
  done < "$members"
  rg -qx 'package/LICENSE' "$members" || fail "$(basename "$tarball") must contain package/LICENSE"
  rg -qx 'package/NOTICE' "$members" || fail "$(basename "$tarball") must contain package/NOTICE"
  cmp -- "$unpack_dir/package/LICENSE" "$package_dir/LICENSE" \
    || fail "$(basename "$tarball") LICENSE differs from the package source"
  cmp -- "$unpack_dir/package/NOTICE" "$package_dir/NOTICE" \
    || fail "$(basename "$tarball") NOTICE differs from the package source"

  node - "$unpack_dir/package/package.json" "$expected_name" "$root_version" <<'NODE'
const fs = require("node:fs");
const [manifestPath, expectedName, version] = process.argv.slice(2);
const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
if (manifest.name !== expectedName || manifest.version !== version) {
  throw new Error(`unexpected package identity: ${manifest.name}@${manifest.version}`);
}
if (JSON.stringify(manifest).includes("workspace:")) {
  throw new Error("published manifest must not contain workspace dependencies");
}
for (const entry of [manifest.exports, manifest.types]) {
  if (typeof entry !== "string" || !entry.startsWith("./dist/") || entry.includes("..")) {
    throw new Error("published entry points must be declarations or code under dist");
  }
}
const dependencies = manifest.dependencies ?? {};
if (expectedName === "@sstlfsj/fibra-client-api" && Object.keys(dependencies).length !== 0) {
  throw new Error("client API must not publish dependencies");
}
if (expectedName === "@sstlfsj/fibra-client-protocol"
  && (Object.keys(dependencies).length !== 1 || dependencies["@sstlfsj/fibra-client-api"] !== version)) {
  throw new Error("client protocol must depend only on the matching published API package");
}
NODE

  if rg -n -i '/(users|home|private|tmp|var)/|\b(clientrunner|runtimeweb|clientreact|react|htmlelement|resourceloader|resourcecache|playwright|transport)\b' "$unpack_dir/package/dist"; then
    fail "$(basename "$tarball") leaks a private, runner, Web, React, or transport implementation"
  fi
}

verify_tarball "$api_tarball" "@sstlfsj/fibra-client-api" "$client_dir/packages/client-api"
verify_tarball "$protocol_tarball" "@sstlfsj/fibra-client-protocol" "$client_dir/packages/client-protocol"

consumer_dir="$tmp_dir/package-consumer"
cp -R "$consumer_source/." "$consumer_dir"
mkdir "$consumer_dir/tarballs"
cp "$api_tarball" "$consumer_dir/tarballs/client-api.tgz"
cp "$protocol_tarball" "$consumer_dir/tarballs/client-protocol.tgz"
node - "$consumer_dir/package.json" "$consumer_dir/pnpm-workspace.yaml" <<'NODE'
const fs = require("node:fs");
const [manifestPath, workspacePath] = process.argv.slice(2);
const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
manifest.dependencies = {
  "@sstlfsj/fibra-client-api": "file:./tarballs/client-api.tgz",
  "@sstlfsj/fibra-client-protocol": "file:./tarballs/client-protocol.tgz",
};
fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
fs.writeFileSync(workspacePath, `packages:\n  - '.'\noverrides:\n  '@sstlfsj/fibra-client-api': 'file:./tarballs/client-api.tgz'\n`);
NODE

env CI=true pnpm --config.update-notifier=false --dir "$consumer_dir" install --offline --ignore-scripts
"$client_dir/node_modules/.bin/tsc" --project "$consumer_dir/tsconfig.json"
node "$consumer_dir/dist/index.js"

#!/usr/bin/env bash
set -euo pipefail
script=$1
temporary_root=$(mktemp -d)
trap 'rm -rf "$temporary_root"' EXIT
python3 - "$temporary_root" <<'PY'
from pathlib import Path
from zipfile import ZipFile
import sys, warnings
root = Path(sys.argv[1])
for name in ('valid stage', 'missing', 'duplicate', 'directory'):
    package = root / name / 'plugins' / 'plugin with spaces' / 'lib'
    package.mkdir(parents=True)
    with ZipFile(package / 'main.jar', 'w') as archive:
        if name == 'missing': archive.writestr('other', b'ignored')
        elif name == 'directory': archive.writestr('META-INF/fibra/config.json/', b'')
        else:
            archive.writestr('META-INF/fibra/config.json', b' { "bytes": "unchanged" }\n')
            if name == 'duplicate':
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore')
                    archive.writestr('META-INF/fibra/config.json', b'duplicate')
(root / 'expected').write_bytes(b' { "bytes": "unchanged" }\n')
PY
for iteration in 1 2; do
  bash "$script" "$temporary_root/valid stage"
  cmp "$temporary_root/expected" "$temporary_root/valid stage/plugins/plugin with spaces/fibra-config.json"
done
for invalid in missing duplicate directory; do
  if bash "$script" "$temporary_root/$invalid" > "$temporary_root/$invalid.log" 2>&1; then
    echo "Expected fixed configuration extraction failure: $invalid" >&2
    exit 1
  fi
  [[ ! -e "$temporary_root/$invalid/plugins/plugin with spaces/fibra-config.json" ]]
done
echo 'Fixed plugin configuration staging verified'

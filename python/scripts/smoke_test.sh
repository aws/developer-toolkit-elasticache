#!/usr/bin/env bash
# Smoke-test the published package from PyPI.
# Retries because the index can lag the upload.
set -euo pipefail

version="$(python3 -c "import pathlib; print(next(pathlib.Path('dist').glob('*.whl')).name.split('-')[1])")"
python3 -m venv /tmp/smoke

installed=false
for attempt in $(seq 1 5); do
  if /tmp/smoke/bin/pip install "developer-toolkit-elasticache==${version}"; then
    installed=true
    break
  fi
  echo "Install attempt ${attempt} failed (index may lag the upload); retrying in 15s"
  sleep 15
done

if [ "$installed" = false ]; then
  echo "::error::Failed to install developer-toolkit-elasticache==${version} from PyPI after 5 attempts."
  exit 1
fi

/tmp/smoke/bin/developer-toolkit-elasticache --help
/tmp/smoke/bin/python -c "import developer_toolkit_elasticache"

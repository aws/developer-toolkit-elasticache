#!/usr/bin/env bash
# Install the package with dev dependencies and run the test suite.
set -euo pipefail

python3 -m pip install -e ".[dev]"
python3 -m pytest -q

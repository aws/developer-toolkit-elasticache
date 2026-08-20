#!/usr/bin/env bash
# Install dependencies and run linting checks.
set -euo pipefail

python3 -m pip install -e ".[dev]"
python3 -m ruff check .
python3 -m ruff format --check .

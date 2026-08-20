#!/usr/bin/env bash
# Build the wheel and sdist, then validate metadata with twine.
# Called from the release workflow's build job.
set -euo pipefail

python3 -m pip install --isolated -r requirements/build.txt
python3 -m build
python3 -m twine check --strict dist/*

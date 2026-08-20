#!/usr/bin/env bash
# Load supported Python versions from the shared JSON file and export as a
# GitHub Actions output for use in downstream matrix jobs.
set -euo pipefail

echo "matrix=$(cat .github/python-versions.json)" >> "$GITHUB_OUTPUT"

#!/usr/bin/env bash
# Verify the release tag matches pyproject.toml and the package is publishable.
# Called from the release workflow's build job.
set -euo pipefail

tag_version="${GITHUB_REF_NAME#python-v}"
pkg_version="$(python3 -c 'import tomllib, pathlib; print(tomllib.loads(pathlib.Path("pyproject.toml").read_text())["project"]["version"])')"

if [ "$tag_version" != "$pkg_version" ]; then
  echo "::error::Release tag is python-v${tag_version} but pyproject.toml declares ${pkg_version}. Bump the version and re-tag."
  exit 1
fi

if grep -q "Private :: Do Not Upload" pyproject.toml; then
  echo "::error::The 'Private :: Do Not Upload' classifier is still set. Remove it in the release commit once the open-source review has cleared."
  exit 1
fi

echo "Publishing version ${pkg_version}."

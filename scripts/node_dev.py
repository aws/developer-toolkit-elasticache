# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
# ruff: noqa: INP001, T201, S603, S607  # a CLI script that prints and runs npm/node

"""Node release smoke test, called by node-release.yml after publishing.

python3 scripts/node_dev.py smoke VERSION
"""

import argparse
import json
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NODE_DIR = ROOT / "node"
EXPORTS = [
    "ConfigurationError",
    "ElastiCacheIAMAuthTokenManager",
    "ElastiCacheIAMAuthTokenProvider",
    "InvalidParameterError",
    "TokenRefreshError",
    "ToolkitInputError",
    "generateIamAuthToken",
]
# The registry's package listing has lagged 5+ minutes behind a publish.
INSTALL_ATTEMPTS = 20
INSTALL_RETRY_SECONDS = 40


def fail(message: str) -> None:
    raise SystemExit(f"::error::{message}")


def smoke(version: str) -> None:
    name = json.loads((NODE_DIR / "package.json").read_text())["name"]
    spec = f"{name}@{version}"
    with tempfile.TemporaryDirectory() as project:
        Path(project, "package.json").write_text('{"private": true, "type": "module"}')
        for attempt in range(1, INSTALL_ATTEMPTS + 1):
            # Exact version, fresh cache, --prefer-online: a cached "not found" must
            # not outlive the registry catching up.
            with tempfile.TemporaryDirectory() as cache:
                command = ["npm", "install", spec, "--no-audit", "--no-fund"]
                command += ["--ignore-scripts", "--prefer-online", "--cache", cache]
                result = subprocess.run(command, cwd=project, check=False)
            if result.returncode == 0:
                break
            if attempt == INSTALL_ATTEMPTS:
                fail(f"Could not install {spec} after {INSTALL_ATTEMPTS} attempts.")
            print(
                f"install attempt {attempt}/{INSTALL_ATTEMPTS} failed (registry may lag "
                f"the upload); retrying in {INSTALL_RETRY_SECONDS}s"
            )
            time.sleep(INSTALL_RETRY_SECONDS)

        manifest = Path(project, "node_modules", *name.split("/"), "package.json")
        installed = json.loads(manifest.read_text())["version"]
        if installed != version:
            fail(f"Installed {name}@{installed}, expected {version}.")
        probe = (
            f"import * as pkg from {json.dumps(name)};"
            f"const missing = {json.dumps(EXPORTS)}.filter((n) => !(n in pkg));"
            "if (missing.length) { console.error(missing.join(', ')); process.exit(1); }"
        )
        check = subprocess.run(
            ["node", "--input-type=module", "-e", probe],
            cwd=project,
            capture_output=True,
            text=True,
            check=False,
        )
        if check.returncode != 0:
            fail(f"Published package is missing exports: {check.stderr.strip()}")
    print(f"Smoke test passed for {spec}.")


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    smoke_parser = commands.add_parser("smoke")
    smoke_parser.add_argument("version")
    args = parser.parse_args()

    if args.command == "smoke":
        smoke(args.version)


if __name__ == "__main__":
    main()

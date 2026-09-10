#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
"""Python task runner — one entry point for every Python CI/CD step, run locally or
from the workflows. Requires Python 3.11+ (tomllib) for `verify` and `list-supported-versions`.

    ./python_dev.py setup                    # install the package + all dev tooling
    ./python_dev.py lint                     # ruff check + format --check
    ./python_dev.py test                     # pytest
    ./python_dev.py package                  # build wheel + sdist, twine check --strict
    ./python_dev.py verify [TAG]             # tag == pyproject version + publish guard + drift
    ./python_dev.py list-supported-versions  # print the supported-version matrix as JSON
    ./python_dev.py smoke [VERSION]          # install from PyPI + assert version + run CLI
    ./python_dev.py ci                       # setup + lint + test + package (pre-flight)

lint/test/package run inside a .venv the runner creates and reuses, so there is no
manual `python -m venv` step; delete python/.venv to rebuild it against a different
interpreter.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import venv
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PYTHON_DIR = REPO_ROOT / "python"
VENV = PYTHON_DIR / ".venv"
PYPROJECT = PYTHON_DIR / "pyproject.toml"
PACKAGE = "developer-toolkit-elasticache"
IMPORT_NAME = "developer_toolkit_elasticache"
DO_NOT_UPLOAD = "Private :: Do Not Upload"


_COMMANDS = {
    "setup": None,
    "lint": None,
    "test": None,
    "package": None,
    "list-supported-versions": None,
    "ci": None,
    "verify": ("tag", "release tag, e.g. python-v1.2.3"),
    "smoke": ("version", "published version, e.g. 1.2.3"),
}


def run(*args: object) -> None:
    print(f"+ {' '.join(map(str, args))}")
    subprocess.run([str(a) for a in args], check=True, cwd=PYTHON_DIR)


def venv_python() -> str:
    """Create the managed .venv on first use and return its Python interpreter.

    Lets the runner own an isolated environment, so `./python_dev.py test` works with no
    manual `python -m venv`. In CI each job is a fresh runner, so the .venv is built
    from that job's interpreter — honouring the matrix version.
    """
    if not VENV.exists():
        print(f"+ creating virtualenv at {VENV}")
        venv.create(VENV, with_pip=True)
    bindir = VENV / ("Scripts" if os.name == "nt" else "bin")
    return str(bindir / "python")


def _project() -> dict:
    import tomllib  # 3.11+; imported lazily so setup/lint/test still load on 3.10

    return tomllib.loads(PYPROJECT.read_text())["project"]


def _support() -> dict:
    import tomllib

    return tomllib.loads(PYPROJECT.read_text())["tool"]["python-support"]


def _ensure_env() -> str:
    """Create python/.venv and install the package + pinned dev tooling into it."""
    py = venv_python()
    run(py, "-m", "pip", "install", "-q", "-e", ".", "-r", "requirements/dev.txt")
    return py


def setup(_args: argparse.Namespace) -> int:
    """Explicitly create python/.venv and install the package + pinned dev tooling."""
    _ensure_env()
    print("Dev environment ready.")
    return 0


def lint(_args: argparse.Namespace) -> int:
    py = _ensure_env()
    run(py, "-m", "ruff", "check", ".")
    run(py, "-m", "ruff", "format", "--check", ".")
    return 0


def test(_args: argparse.Namespace) -> int:
    py = _ensure_env()
    run(py, "-m", "pytest", "-q")
    return 0


def package(_args: argparse.Namespace) -> int:
    py = _ensure_env()
    run(py, "-m", "build")
    dist = sorted(str(p) for p in (PYTHON_DIR / "dist").glob("*"))
    if not dist:
        raise SystemExit("build produced no artifacts in dist/")
    run(py, "-m", "twine", "check", "--strict", *dist)
    return 0


def _support_drift(project: dict, support: dict) -> str | None:
    """Report if [tool.python-support] disagrees with requires-python or the Python
    classifiers, else None."""
    expected_requires = f">={support['min']}"
    if project.get("requires-python") != expected_requires:
        return (
            f"requires-python is {project.get('requires-python')!r} but "
            f"[tool.python-support].min implies {expected_requires!r}."
        )
    lo, hi = int(support["min"].split(".")[1]), int(support["max"].split(".")[1])
    expected = [f"3.{minor}" for minor in range(lo, hi + 1)]
    declared = sorted(
        m.group(1)
        for c in project.get("classifiers", [])
        if (m := re.match(r"Programming Language :: Python :: (3\.\d+)$", c))
    )
    if declared != expected:
        return f"Python classifiers {declared} do not match python-support {expected}."
    return None


def verify(args: argparse.Namespace) -> int:
    tag = args.tag or os.environ.get("GITHUB_REF_NAME", "")
    tag_version = tag.removeprefix("python-v")
    project = _project()
    pkg_version = project["version"]
    if tag_version != pkg_version:
        print(
            "::error::Release tag does not match the version in pyproject.toml "
            f"({pkg_version}). Bump the version and re-tag."
        )
        return 1
    if DO_NOT_UPLOAD in project.get("classifiers", []):
        print(
            f"::error::The '{DO_NOT_UPLOAD}' classifier is still set. Remove it in the "
            "release commit once the open-source review has cleared."
        )
        return 1
    drift = _support_drift(project, _support())
    if drift:
        print(f"::error::{drift}")
        return 1
    print(f"Publishing version {pkg_version}.")
    return 0


def list_supported_versions(_args: argparse.Namespace) -> int:
    support = _support()
    lo_major, lo_minor = (int(p) for p in support["min"].split(".")[:2])
    hi_major, hi_minor = (int(p) for p in support["max"].split(".")[:2])
    if lo_major != hi_major:
        raise SystemExit("[tool.python-support] min and max must share a major version.")
    vers = [f"{lo_major}.{minor}" for minor in range(lo_minor, hi_minor + 1)]
    if not vers:
        raise SystemExit("[tool.python-support] min is greater than max.")
    print(json.dumps({"all": vers, "min": vers[0], "max": vers[-1]}))
    return 0


def smoke(args: argparse.Namespace) -> int:
    version = args.version or os.environ.get("GITHUB_REF_NAME", "").removeprefix(
        "python-v"
    )
    if not version:
        wheels = sorted((PYTHON_DIR / "dist").glob("*.whl"))
        if not wheels:
            print("::error::No version given and no wheel in dist/ to derive it from.")
            return 1
        version = wheels[0].name.split("-")[1]

    with tempfile.TemporaryDirectory() as tmp:
        env = Path(tmp) / "venv"
        venv.create(env, with_pip=True)
        bindir = env / ("Scripts" if os.name == "nt" else "bin")
        pip, python, cli = (
            str(bindir / "pip"),
            str(bindir / "python"),
            str(bindir / PACKAGE),
        )

        spec = f"{PACKAGE}=={version}"
        for attempt in range(1, 6):
            if subprocess.run([pip, "install", spec], check=False).returncode == 0:
                break
            print(
                f"install attempt {attempt} failed (index may lag the upload); retrying in 15s"
            )
            time.sleep(15)
        else:
            print(f"::error::Could not install {PACKAGE} from PyPI after 5 attempts.")
            return 1

        got = subprocess.run(
            [
                python,
                "-c",
                f"import importlib.metadata as m; print(m.version('{PACKAGE}'))",
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        if got != version:
            print(f"::error::Installed the wrong version of {PACKAGE} ({got}).")
            return 1

        subprocess.run([cli, "--help"], check=True)
        subprocess.run([python, "-c", f"import {IMPORT_NAME}"], check=True)

    print(f"Smoke test passed for {PACKAGE}.")
    return 0


def ci(args: argparse.Namespace) -> int:
    lint(args)
    test(args)
    package(args)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Python task runner.")
    subparsers = parser.add_subparsers(dest="command", required=True)
    for name, arg in _COMMANDS.items():
        subparser = subparsers.add_parser(name)
        if arg is not None:
            subparser.add_argument(arg[0], nargs="?", help=arg[1])
        subparser.set_defaults(func=globals()[name.replace("-", "_")])

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

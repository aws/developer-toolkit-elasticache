# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
# ruff: noqa: INP001, T201, S310, S314, PLR2004  # CLI: prints, parses our own pom, fixed https URLs

import argparse
import http.client
import json
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POM = ROOT / "java" / "pom.xml"
CHANGELOG = ROOT / "java" / "CHANGELOG.md"
DEPENDENT_POMS = [
    ROOT / "java" / "examples" / "pom.xml",
    ROOT / "java" / "integration-tests" / "pom.xml",
]
MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
TAG_PREFIX = "java-v"
CENTRAL = "https://repo1.maven.org/maven2"
# Maven Central usually serves a newly published release within 10-30 minutes.
SMOKE_ATTEMPTS = 45
SMOKE_RETRY_SECONDS = 60


def fail(message: str) -> None:
    raise SystemExit(f"::error::{message}")


def pom_field(name: str) -> str:
    value = ET.parse(POM).getroot().findtext(f"m:{name}", namespaces=MAVEN_NAMESPACE)
    if not value:
        fail(f"<{name}> is missing from java/pom.xml")
    return value


def list_supported_versions() -> None:
    root = ET.parse(POM).getroot()
    value = root.findtext(
        "m:properties/m:java.supported.versions", namespaces=MAVEN_NAMESPACE
    )
    if value is None:
        raise SystemExit("java.supported.versions is missing from java/pom.xml")

    versions = json.loads(value)
    if (
        not isinstance(versions, list)
        or not versions
        or not all(isinstance(version, str) and version for version in versions)
    ):
        raise SystemExit("java.supported.versions must be a non-empty JSON string array")
    result = {"all": versions, "min": versions[0], "max": versions[-1]}
    print(json.dumps(result, separators=(",", ":")))


def verify(tag: str) -> None:
    version = pom_field("version")
    if tag != f"{TAG_PREFIX}{version}":
        fail(f"Release tag version does not match java/pom.xml version {version}.")
    if version.endswith("-SNAPSHOT"):
        fail(f"java/pom.xml version {version} is a SNAPSHOT; set the release version.")
    if not CHANGELOG.is_file() or f"## [{version}]" not in CHANGELOG.read_text():
        fail(f"java/CHANGELOG.md has no [{version}] entry.")
    # The examples and integration tests depend on the published artifact.
    for pom in DEPENDENT_POMS:
        depends_on = (
            ET.parse(pom)
            .getroot()
            .findtext(
                f"m:dependencies/m:dependency[m:artifactId='{pom_field('artifactId')}']/m:version",
                namespaces=MAVEN_NAMESPACE,
            )
        )
        if depends_on != version:
            fail(f"{pom.relative_to(ROOT)} depends on {depends_on}, not {version}.")
    print(f"Publishing version {version}.")


def smoke(version: str) -> None:
    group = pom_field("groupId")
    artifact = pom_field("artifactId")
    base = (
        f"{CENTRAL}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}"
    )
    files = [".pom", ".jar", "-sources.jar", "-javadoc.jar", ".jar.asc"]
    for attempt in range(1, SMOKE_ATTEMPTS + 1):
        missing = [suffix for suffix in files if not exists(base + suffix)]
        if not missing:
            print(f"Smoke test passed for {group}:{artifact}:{version}.")
            return
        if attempt == SMOKE_ATTEMPTS:
            fail(f"{group}:{artifact}:{version} is not on Maven Central: {missing}.")
        print(
            f"attempt {attempt}/{SMOKE_ATTEMPTS}: not on Maven Central yet "
            f"({', '.join(missing)}); retrying in {SMOKE_RETRY_SECONDS}s",
            flush=True,
        )
        time.sleep(SMOKE_RETRY_SECONDS)


def exists(url: str) -> bool:
    request = urllib.request.Request(url, method="HEAD")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status == 200
    except (OSError, http.client.HTTPException):
        return False


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("list-supported-versions")
    commands.add_parser("verify").add_argument("tag")
    commands.add_parser("smoke").add_argument("version")
    args = parser.parse_args()

    if args.command == "list-supported-versions":
        list_supported_versions()
    elif args.command == "verify":
        verify(args.tag)
    elif args.command == "smoke":
        smoke(args.version)


if __name__ == "__main__":
    main()

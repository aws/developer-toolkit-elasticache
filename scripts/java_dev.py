# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POM = ROOT / "java" / "pom.xml"
MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


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
        raise SystemExit(
            "java.supported.versions must be a non-empty JSON string array"
        )
    result = {"all": versions, "min": versions[0], "max": versions[-1]}
    print(json.dumps(result, separators=(",", ":")))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["list-supported-versions"])
    args = parser.parse_args()

    if args.command == "list-supported-versions":
        list_supported_versions()


if __name__ == "__main__":
    main()

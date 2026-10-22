// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const PACKAGE_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const SCRIPT_PATH = join(PACKAGE_ROOT, "scripts", "list-supported-versions.mjs");

function runListSupportedVersions(packageJson: Record<string, unknown>) {
  const temporaryRoot = mkdtempSync(join(tmpdir(), "elasticache-node-versions-"));
  const scriptsDirectory = join(temporaryRoot, "scripts");
  const temporaryScript = join(scriptsDirectory, "list-supported-versions.mjs");

  try {
    mkdirSync(scriptsDirectory);
    copyFileSync(SCRIPT_PATH, temporaryScript);
    writeFileSync(join(temporaryRoot, "package.json"), JSON.stringify(packageJson));
    return spawnSync(process.execPath, [temporaryScript], {
      encoding: "utf8",
    });
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
}

test("prints the supported Node.js version matrix", () => {
  const result = runListSupportedVersions({
    engines: { node: ">=22" },
    supportedNodeVersions: ["22.x", "24.x"],
  });

  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), {
    all: ["22.x", "24.x"],
    min: "22.x",
    max: "24.x",
  });
});

const invalidMetadataCases = [
  {
    name: "rejects a missing supported version list",
    packageJson: { engines: { node: ">=22" } },
    expectedError: /must be a non-empty array/,
  },
  {
    name: "rejects an empty supported version list",
    packageJson: {
      engines: { node: ">=22" },
      supportedNodeVersions: [],
    },
    expectedError: /must be a non-empty array/,
  },
  {
    name: "rejects a non-array supported version list",
    packageJson: {
      engines: { node: ">=22" },
      supportedNodeVersions: "22.x",
    },
    expectedError: /must be a non-empty array/,
  },
  {
    name: "rejects malformed supported versions",
    packageJson: {
      engines: { node: ">=22" },
      supportedNodeVersions: ["22"],
    },
    expectedError: /expected "<major>\.x"/,
  },
  {
    name: "rejects duplicate supported versions",
    packageJson: {
      engines: { node: ">=22" },
      supportedNodeVersions: ["22.x", "22.x"],
    },
    expectedError: /must not contain duplicates/,
  },
  {
    name: "rejects unordered supported versions",
    packageJson: {
      engines: { node: ">=22" },
      supportedNodeVersions: ["24.x", "22.x"],
    },
    expectedError: /must be ordered from oldest to newest/,
  },
  {
    name: "rejects an engine that differs from the oldest supported version",
    packageJson: {
      engines: { node: ">=20" },
      supportedNodeVersions: ["22.x", "24.x"],
    },
    expectedError: /must match the oldest supported Node\.js version/,
  },
] as const;

for (const { name, packageJson, expectedError } of invalidMetadataCases) {
  test(name, () => {
    const result = runListSupportedVersions(packageJson);

    assert.notEqual(result.status, 0);
    assert.match(result.stderr, expectedError);
  });
}

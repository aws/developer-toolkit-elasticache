// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const PACKAGE_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const PACKAGE_NAME = "@aws/developer-toolkit-elasticache";
const NPM_INSTALL_TIMEOUT_MS = 60_000;

type PackedFile = {
  path: string;
};

type PackResult = {
  filename: string;
  files: PackedFile[];
};

test("packed package installs and exposes its public API", () => {
  const temporaryRoot = mkdtempSync(join(tmpdir(), "elasticache-package-test-"));
  const packageDirectory = join(temporaryRoot, "package");
  const installationDirectory = join(temporaryRoot, "installed");
  const npmCacheDirectory = join(temporaryRoot, "npm-cache");
  const npmCommand = process.platform === "win32" ? "npm.cmd" : "npm";

  try {
    mkdirSync(packageDirectory);
    mkdirSync(installationDirectory);
    const pack = spawnSync(
      npmCommand,
      ["pack", "--json", "--pack-destination", packageDirectory],
      {
        cwd: PACKAGE_ROOT,
        encoding: "utf8",
        env: {
          ...process.env,
          npm_config_cache: npmCacheDirectory,
          npm_config_update_notifier: "false",
        },
      },
    );
    assert.equal(pack.status, 0, pack.stderr);

    const packResult = JSON.parse(pack.stdout) as PackResult[];
    const packed = packResult[0];
    assert.ok(packed);
    assert.equal(
      packed.files.some((file) => file.path === "examples/generate-token.mjs"),
      true,
    );
    assert.equal(
      packed.files.some((file) => file.path === "examples/connect-valkey.mjs"),
      true,
    );

    const tarballPath = join(packageDirectory, packed.filename);
    const install = spawnSync(
      npmCommand,
      [
        "install",
        tarballPath,
        "--ignore-scripts",
        "--no-audit",
        "--no-fund",
        "--prefix",
        installationDirectory,
      ],
      {
        cwd: PACKAGE_ROOT,
        encoding: "utf8",
        timeout: NPM_INSTALL_TIMEOUT_MS,
        env: {
          ...process.env,
          npm_config_cache: npmCacheDirectory,
          npm_config_update_notifier: "false",
        },
      },
    );
    assert.equal(
      install.status,
      0,
      install.signal
        ? `npm install was killed with ${install.signal} (likely no registry access; ` +
            `this test installs the packed tarball's dependencies from the npm registry)`
        : install.stderr,
    );

    const installedNodeModules = join(installationDirectory, "node_modules");
    const exportsProbePath = join(installedNodeModules, "exports-probe.mjs");
    writeFileSync(
      exportsProbePath,
      [
        'import assert from "node:assert/strict";',
        'import { ConfigurationError, ElastiCacheIAMAuthTokenProvider, InvalidParameterError, ToolkitInputError, generateIamAuthToken } from "@aws/developer-toolkit-elasticache";',
        'assert.equal(typeof generateIamAuthToken, "function");',
        'assert.equal(typeof ElastiCacheIAMAuthTokenProvider, "function");',
        "assert.equal(ConfigurationError.prototype instanceof ToolkitInputError, true);",
        "assert.equal(ConfigurationError.prototype instanceof InvalidParameterError, false);",
      ].join("\n"),
    );
    const exportsProbe = spawnSync(process.execPath, [exportsProbePath], {
      cwd: installationDirectory,
      encoding: "utf8",
    });
    assert.equal(exportsProbe.status, 0, exportsProbe.stderr);
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
});

test("package root exports the complete public API", async () => {
  const packageModule = await import(PACKAGE_NAME);

  assert.equal(typeof packageModule.generateIamAuthToken, "function");
  assert.equal(typeof packageModule.ElastiCacheIAMAuthTokenProvider, "function");
  assert.equal(typeof packageModule.ToolkitInputError, "function");
  assert.equal(typeof packageModule.InvalidParameterError, "function");
  assert.equal(typeof packageModule.ConfigurationError, "function");
  assert.equal(
    packageModule.ConfigurationError.prototype instanceof packageModule.ToolkitInputError,
    true,
  );
  assert.equal(
    packageModule.ConfigurationError.prototype instanceof
      packageModule.InvalidParameterError,
    false,
  );
});

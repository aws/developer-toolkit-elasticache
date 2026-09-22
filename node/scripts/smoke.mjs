// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

// Post-publish smoke test, run by node-release.yml after npm publish:
//
//   node scripts/smoke.mjs [VERSION]
//
// Installs the just-published version from the npm registry into a temporary project
// (retrying while the registry catches up), imports it, and checks that the public API
// is exported and that the installed version is the one that was released.

import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { argv, env, execPath, exit, platform, stdout } from "node:process";
import { setTimeout as sleep } from "node:timers/promises";

const PACKAGE_NAME = "@aws/developer-toolkit-elasticache";
const TAG_PREFIX = "node-v";
const ATTEMPTS = 5;
const RETRY_DELAY_MS = 15_000;
const EXPECTED_EXPORTS = [
  "ConfigurationError",
  "ElastiCacheIAMAuthTokenManager",
  "ElastiCacheIAMAuthTokenProvider",
  "InvalidParameterError",
  "TokenRefreshError",
  "ToolkitInputError",
  "generateIamAuthToken",
];

function fail(message) {
  stdout.write(`::error::${message}\n`);
  exit(1);
}

const version = argv[2] || (env.GITHUB_REF_NAME ?? "").replace(TAG_PREFIX, "");
if (!version) {
  fail("No version given and none could be derived from GITHUB_REF_NAME.");
}

const npm = platform === "win32" ? "npm.cmd" : "npm";
const project = mkdtempSync(join(tmpdir(), "elasticache-smoke-"));
try {
  writeFileSync(join(project, "package.json"), JSON.stringify({ type: "module" }));
  const spec = `${PACKAGE_NAME}@${version}`;

  let installed = false;
  for (let attempt = 1; attempt <= ATTEMPTS && !installed; attempt++) {
    const result = spawnSync(
      npm,
      ["install", spec, "--no-audit", "--no-fund", "--ignore-scripts"],
      { cwd: project, encoding: "utf8", shell: platform === "win32" },
    );
    installed = result.status === 0;
    if (!installed && attempt < ATTEMPTS) {
      stdout.write(
        `install attempt ${attempt} failed (registry may lag the upload); ` +
          `retrying in ${RETRY_DELAY_MS / 1000}s\n`,
      );
      await sleep(RETRY_DELAY_MS);
    }
  }
  if (!installed) {
    fail(`Could not install ${spec} from the npm registry after ${ATTEMPTS} attempts.`);
  }

  // The probe runs inside the temporary project so it resolves the installed copy.
  const probe = join(project, "probe.mjs");
  const manifestSpecifier = JSON.stringify(`${PACKAGE_NAME}/package.json`);
  writeFileSync(
    probe,
    [
      `import * as pkg from ${JSON.stringify(PACKAGE_NAME)};`,
      `import { createRequire } from "node:module";`,
      `const manifest = createRequire(import.meta.url)(${manifestSpecifier});`,
      `const missing = ${JSON.stringify(EXPECTED_EXPORTS)}.filter((name) => !(name in pkg));`,
      `if (missing.length) throw new Error("missing exports: " + missing.join(", "));`,
      `if (manifest.version !== ${JSON.stringify(version)})`,
      `  throw new Error("installed " + manifest.version + ", expected " + ${JSON.stringify(version)});`,
    ].join("\n"),
  );
  const check = spawnSync(execPath, [probe], { cwd: project, encoding: "utf8" });
  if (check.status !== 0) {
    const reason =
      check.stderr.split("\n").find((line) => line.startsWith("Error:")) ??
      check.stderr.trim();
    fail(`Published package failed the import check: ${reason}`);
  }
} finally {
  rmSync(project, { recursive: true, force: true });
}

stdout.write(`Smoke test passed for ${PACKAGE_NAME}@${version}.\n`);

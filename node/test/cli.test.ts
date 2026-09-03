// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const CLI_PATH = fileURLToPath(new URL("../src/cli.js", import.meta.url));
const ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";
const SECRET_ACCESS_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

function runCli(args: string[], overrides: NodeJS.ProcessEnv = {}) {
  const environment: NodeJS.ProcessEnv = {
    ...process.env,
    AWS_ACCESS_KEY_ID: ACCESS_KEY_ID,
    AWS_SECRET_ACCESS_KEY: SECRET_ACCESS_KEY,
    AWS_REGION: "us-east-1",
    AWS_EC2_METADATA_DISABLED: "true",
  };
  delete environment.AWS_PROFILE;
  delete environment.AWS_DEFAULT_REGION;
  for (const [key, value] of Object.entries(overrides)) {
    if (value === undefined) {
      delete environment[key];
    } else {
      environment[key] = value;
    }
  }
  return spawnSync(process.execPath, [CLI_PATH, ...args], {
    encoding: "utf8",
    env: environment,
  });
}

test("writes only the token to stdout", () => {
  const result = runCli([
    "--serverless-cache-name",
    "my-cache",
    "--user-id",
    "testuser",
    "--region",
    "us-east-1",
  ]);

  assert.equal(result.status, 0);
  assert.equal(result.stderr, "");
  assert.match(result.stdout, /^my-cache\/\?Action=connect&User=testuser/);
  assert.match(result.stdout, /X-Amz-Signature=[0-9a-f]{64}\n$/);
});

test("accepts the Python-compatible leading command", () => {
  const result = runCli([
    "generate_iam_auth_token",
    "--replication-group-id",
    "my-group",
    "--user-id",
    "testuser",
  ]);

  assert.equal(result.status, 0);
  assert.match(result.stdout, /^my-group\/\?Action=connect&User=testuser/);
  assert.equal(result.stdout.includes("ResourceType"), false);
});

test("reports toolkit input errors on one stderr line with exit 1", () => {
  const result = runCli([
    "--serverless-cache-name",
    "evil.com/inject",
    "--user-id",
    "testuser",
  ]);

  assert.equal(result.status, 1);
  assert.equal(result.stdout, "");
  assert.match(result.stderr, /^Error: .*\n$/);
  assert.equal(result.stderr.includes("evil.com/inject"), false);
});

test("returns exit 2 for malformed arguments", () => {
  const missingTarget = runCli(["--user-id", "testuser"]);
  assert.equal(missingTarget.status, 2);
  assert.match(missingTarget.stderr, /^Error: .*\n$/);

  const bothTargets = runCli([
    "--serverless-cache-name",
    "my-cache",
    "--replication-group-id",
    "my-group",
    "--user-id",
    "testuser",
  ]);
  assert.equal(bothTargets.status, 2);

  const unknownOption = runCli(["--unknown", "value"]);
  assert.equal(unknownOption.status, 2);
});

test("returns exit 2 for missing values, positionals, and no arguments", () => {
  const malformedInvocations = [[], ["--serverless-cache-name"], ["unexpected"]];

  for (const args of malformedInvocations) {
    const result = runCli(args);
    assert.equal(result.status, 2);
    assert.equal(result.stdout, "");
    assert.match(result.stderr, /^Error: [^\r\n]*\n$/);
  }
});

test("reports missing credentials as a one-line configuration error", () => {
  const result = runCli(
    ["--serverless-cache-name", "my-cache", "--user-id", "testuser"],
    {
      AWS_ACCESS_KEY_ID: undefined,
      AWS_SECRET_ACCESS_KEY: undefined,
      AWS_SESSION_TOKEN: undefined,
      AWS_PROFILE: undefined,
      AWS_DEFAULT_PROFILE: undefined,
      AWS_WEB_IDENTITY_TOKEN_FILE: undefined,
      AWS_ROLE_ARN: undefined,
      AWS_CONTAINER_CREDENTIALS_RELATIVE_URI: undefined,
      AWS_CONTAINER_CREDENTIALS_FULL_URI: undefined,
      AWS_CONFIG_FILE: "/dev/null",
      AWS_SHARED_CREDENTIALS_FILE: "/dev/null",
    },
  );

  assert.equal(result.status, 1);
  assert.equal(result.stdout, "");
  assert.equal(
    result.stderr,
    "Error: No AWS credentials found. Configure credentials via the environment, " +
      "shared config/credentials files, or an instance/container role.\n",
  );
});

test("prints help without importing or invoking the signing chain", () => {
  const result = runCli(["--help"]);
  assert.equal(result.status, 0);
  assert.equal(result.stderr, "");
  assert.match(result.stdout, /^Usage: generate_iam_auth_token/m);
});

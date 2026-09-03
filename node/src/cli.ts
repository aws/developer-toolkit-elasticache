#!/usr/bin/env node

// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import { parseArgs } from "node:util";
import { realpathSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

import { ToolkitInputError } from "./errors.js";
import { generateIamAuthToken } from "./token-generator.js";

const COMMAND = "generate_iam_auth_token";
const USAGE = `Usage: ${COMMAND} [options]

Generate an IAM auth token for an ElastiCache cache.

Options:
  --serverless-cache-name <name>  Serverless cache name
  --replication-group-id <id>     Node-based replication group id
  --user-id <id>                  ElastiCache user id
  --region <region>               AWS region
  --help                          Show this help
`;

type ParsedArguments =
  | { help: true }
  | {
      help: false;
      userId: string;
      region?: string;
      serverlessCacheName?: string;
      replicationGroupId?: string;
    };

function parserMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  // Keep stderr errors to one line so hostile arguments cannot inject extra
  // log lines through embedded LF or CRLF characters.
  return message.replace(/[\r\n]+/g, " ");
}

function readString(
  values: Record<string, string | boolean | undefined>,
  option: string,
): string | undefined {
  const value = values[option];
  if (value === undefined) {
    return undefined;
  }
  if (typeof value !== "string") {
    throw new TypeError(`Option '--${option}' requires a value.`);
  }
  return value;
}

function parseCliArguments(argv: string[]): ParsedArguments {
  let args = argv;
  if (args[0] === COMMAND) {
    // Also accept the Python-compatible form documented in the README, where the
    // executable name is repeated as a leading command argument.
    args = args.slice(1);
  }
  if (args.length === 0) {
    throw new TypeError("Missing required options.");
  }

  const { values } = parseArgs({
    args,
    allowPositionals: false,
    options: {
      "serverless-cache-name": { type: "string" },
      "replication-group-id": { type: "string" },
      "user-id": { type: "string" },
      region: { type: "string" },
      help: { type: "boolean", short: "h" },
    },
    strict: true,
  });

  if (values.help) {
    return { help: true };
  }

  const serverlessCacheName = readString(values, "serverless-cache-name");
  const replicationGroupId = readString(values, "replication-group-id");
  const userId = readString(values, "user-id");
  const region = readString(values, "region");
  const hasServerlessTarget = serverlessCacheName !== undefined;
  const hasReplicationGroupTarget = replicationGroupId !== undefined;

  if (hasServerlessTarget === hasReplicationGroupTarget) {
    throw new TypeError(
      "Exactly one of '--serverless-cache-name' and '--replication-group-id' is required.",
    );
  }
  if (userId === undefined) {
    throw new TypeError("Option '--user-id' is required.");
  }

  return {
    help: false,
    userId,
    region,
    serverlessCacheName,
    replicationGroupId,
  };
}

export async function main(argv: string[] = process.argv.slice(2)): Promise<number> {
  let arguments_: ParsedArguments;
  try {
    arguments_ = parseCliArguments(argv);
  } catch (error) {
    process.stderr.write(`Error: ${parserMessage(error)}\n`);
    return 2;
  }

  if (arguments_.help) {
    process.stdout.write(USAGE);
    return 0;
  }

  try {
    const token = await generateIamAuthToken({
      userId: arguments_.userId,
      region: arguments_.region,
      serverlessCacheName: arguments_.serverlessCacheName,
      replicationGroupId: arguments_.replicationGroupId,
    });
    process.stdout.write(`${token}\n`);
    return 0;
  } catch (error) {
    if (error instanceof ToolkitInputError) {
      process.stderr.write(`Error: ${parserMessage(error)}\n`);
      return 1;
    }
    throw error;
  }
}

function isMainModule(): boolean {
  if (!process.argv[1]) {
    return false;
  }

  try {
    // npm's installed or globally-linked bin entry is commonly a symlink, so
    // compare canonical filesystem paths rather than the raw argument strings.
    return (
      realpathSync(fileURLToPath(import.meta.url)) ===
      realpathSync(resolve(process.argv[1]))
    );
  } catch {
    return false;
  }
}

if (isMainModule()) {
  const exitCode = await main();
  process.exitCode = exitCode;
}

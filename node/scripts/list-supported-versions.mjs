// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from "node:fs";
import { stdout } from "node:process";
import { URL } from "node:url";

const packageJson = JSON.parse(
  readFileSync(new URL("../package.json", import.meta.url), "utf8"),
);
const versions = packageJson.supportedNodeVersions;

if (!Array.isArray(versions) || versions.length === 0) {
  throw new Error("package.json supportedNodeVersions must be a non-empty array.");
}

const majors = versions.map((version) => {
  const match = /^(\d+)\.x$/.exec(version);
  if (!match) {
    throw new Error(
      `Unsupported Node.js version ${JSON.stringify(version)}: expected "<major>.x".`,
    );
  }
  return Number(match[1]);
});

if (new Set(majors).size !== majors.length) {
  throw new Error("package.json supportedNodeVersions must not contain duplicates.");
}

if (majors.some((major, index) => index > 0 && major <= majors[index - 1])) {
  throw new Error(
    "package.json supportedNodeVersions must be ordered from oldest to newest.",
  );
}

const engineMatch = /^>=(\d+)$/.exec(packageJson.engines?.node ?? "");
if (!engineMatch || Number(engineMatch[1]) !== majors[0]) {
  throw new Error(
    "package.json engines.node must match the oldest supported Node.js version.",
  );
}

stdout.write(
  JSON.stringify({
    all: versions,
    min: versions[0],
    max: versions.at(-1),
  }),
);

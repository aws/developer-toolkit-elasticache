// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

/* global console */

/**
 * Run this example after installing iovalkey:
 *
 *   npm install iovalkey
 *
 * The cache name used for signing is different from the connection endpoint.
 * For a node-based deployment, use replicationGroupId instead.
 *
 * iovalkey does not generate ElastiCache IAM authentication tokens, and it
 * cannot re-authenticate an existing connection. This example uses
 * ElastiCacheIAMAuthTokenManager, which caches one token and refreshes it in
 * the background, so every new client is created with a valid token without
 * signing a new one per connection. Automatic reconnects are disabled because
 * they would reuse the token the connection was opened with; long-running
 * applications should create a replacement client, calling getToken() again,
 * whenever the connection is lost.
 */
import { Valkey } from "iovalkey";
import { ElastiCacheIAMAuthTokenManager } from "@aws/developer-toolkit-elasticache";

const ENDPOINT = "my-cache-abc123.serverless.use1.cache.amazonaws.com";

const auth = await ElastiCacheIAMAuthTokenManager.create({
  serverlessCacheName: "my-cache",
  userId: "my-iam-user",
  region: "us-east-1",
  // Never log the token itself; it is a bearer credential.
  onTokenChanged: () => console.log("installed a new IAM auth token"),
});

async function connect() {
  return new Valkey({
    host: ENDPOINT,
    port: 6379,
    username: auth.userId,
    password: await auth.getToken(),
    tls: {},
    retryStrategy: () => null,
  });
}

try {
  const client = await connect();
  try {
    await client.set("hello", "world");
    console.log(await client.get("hello"));
  } finally {
    client.disconnect();
  }

  // A second connection reuses the cached token instead of signing another one.
  const replacement = await connect();
  try {
    console.log(await replacement.get("hello"));
  } finally {
    replacement.disconnect();
  }
} finally {
  auth.close();
}

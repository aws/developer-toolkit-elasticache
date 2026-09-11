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
 * iovalkey does not generate ElastiCache IAM authentication tokens. This
 * short-lived example disables automatic reconnects because they would reuse
 * the initial token. Long-running applications should create a replacement
 * client with a fresh token whenever the connection is lost.
 */
import { Valkey } from "iovalkey";
import { ElastiCacheIAMAuthTokenProvider } from "@aws/developer-toolkit-elasticache";

const auth = await ElastiCacheIAMAuthTokenProvider.create({
  serverlessCacheName: "my-cache",
  userId: "my-iam-user",
  region: "us-east-1",
});

const client = new Valkey({
  host: "my-cache-abc123.serverless.use1.cache.amazonaws.com",
  port: 6379,
  username: auth.userId,
  password: await auth.getToken(),
  tls: {},
  retryStrategy: () => null,
});

try {
  await client.set("hello", "world");
  console.log(await client.get("hello"));
} finally {
  client.disconnect();
}

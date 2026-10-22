// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

/* global console */

/**
 * Run this example after installing redis:
 *
 *   npm install redis
 *
 * The cache name used for signing is different from the connection endpoint.
 * For a node-based deployment, use replicationGroupId instead.
 *
 * node-redis calls an AsyncCredentialsProvider's credentials() on every
 * connection handshake, including its own automatic reconnects. Backing it
 * with ElastiCacheIAMAuthTokenManager.getCredentials() means every handshake
 * gets a valid cached token without signing a new one per connection, and
 * node-redis's built-in reconnect strategy can stay enabled.
 */
import { createClient } from "redis";
import { ElastiCacheIAMAuthTokenManager } from "@aws/developer-toolkit-elasticache";

const ENDPOINT = "my-cache-abc123.serverless.use1.cache.amazonaws.com";

const auth = await ElastiCacheIAMAuthTokenManager.create({
  serverlessCacheName: "my-cache",
  userId: "my-iam-user",
  region: "us-east-1",
  // Never log the token itself; it is a bearer credential.
  onTokenChanged: () => console.log("installed a new IAM auth token"),
});

const client = createClient({
  socket: {
    host: ENDPOINT,
    port: 6379,
    tls: true,
  },
  credentialsProvider: {
    type: "async-credentials-provider",
    credentials: async () => {
      const [username, password] = await auth.getCredentials();
      return { username, password };
    },
  },
});
client.on("error", (error) => console.error("node-redis client error", error));

try {
  await client.connect();
  await client.set("hello", "world");
  console.log(await client.get("hello"));
} finally {
  await client.close();
  auth.close();
}

// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

/* global console, setTimeout, clearTimeout */

/**
 * Run this example after installing @valkey/valkey-glide:
 *
 *   npm install @valkey/valkey-glide
 *
 * The cache name used for signing is different from the connection endpoint.
 * For a node-based deployment, use replicationGroupId instead.
 *
 * The token is a 15-minute bearer credential. GLIDE reuses the credentials
 * passed at connection time for its own automatic reconnects, so a
 * long-running process must refresh the client's password on an interval
 * shorter than the token TTL. `updateConnectionPassword` rotates it without
 * dropping the connection.
 */
import { GlideClient } from "@valkey/valkey-glide";
import { ElastiCacheIAMAuthTokenProvider } from "@aws/developer-toolkit-elasticache";

const TOKEN_REFRESH_INTERVAL_MS = 10 * 60 * 1000;
// A failed refresh gets retried well before the 15-minute token TTL expires,
// rather than waiting for the next full 10-minute interval.
const TOKEN_REFRESH_RETRY_MS = 30 * 1000;

// create() resolves the region up front, so a missing region fails here rather
// than at the first token request.
const auth = await ElastiCacheIAMAuthTokenProvider.create({
  serverlessCacheName: "my-cache",
  userId: "my-iam-user",
  region: "us-east-1",
});

const client = await GlideClient.createClient({
  addresses: [
    {
      host: "my-cache-abc123.serverless.use1.cache.amazonaws.com",
      port: 6379,
    },
  ],
  credentials: {
    username: auth.userId,
    password: await auth.getToken(),
  },
  useTLS: true,
});

let refreshTimer;
function scheduleTokenRefresh(delayMs) {
  refreshTimer = setTimeout(async () => {
    try {
      const token = await auth.getToken();
      await client.updateConnectionPassword(token, true);
      scheduleTokenRefresh(TOKEN_REFRESH_INTERVAL_MS);
    } catch (error) {
      console.error("Failed to refresh IAM auth token:", error);
      scheduleTokenRefresh(TOKEN_REFRESH_RETRY_MS);
    }
  }, delayMs);
  refreshTimer.unref?.();
}
scheduleTokenRefresh(TOKEN_REFRESH_INTERVAL_MS);

await client.set("hello", "world");
console.log(await client.get("hello"));

clearTimeout(refreshTimer);
client.close();

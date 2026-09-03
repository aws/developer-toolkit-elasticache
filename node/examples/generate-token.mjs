// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

/* global console */

import { generateIamAuthToken } from "@aws/developer-toolkit-elasticache";

const token = await generateIamAuthToken({
  serverlessCacheName: "my-cache",
  userId: "my-iam-user",
  region: "us-east-1",
});

// Do not print the token. It is a short-lived bearer credential.
console.log(`Generated an IAM auth token (${token.length} characters).`);

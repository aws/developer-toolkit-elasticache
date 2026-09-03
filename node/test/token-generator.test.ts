// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import assert from "node:assert/strict";
import { test } from "node:test";

import {
  ConfigurationError,
  ElastiCacheIAMAuthTokenProvider,
  InvalidParameterError,
  ToolkitInputError,
  generateIamAuthToken,
} from "../src/index.js";

const CACHE = "my-cache";
const USER = "testuser";
const REGION = "us-east-1";
const SESSION_REGION = "eu-west-1";
const CREDENTIALS = {
  accessKeyId: "AKIAIOSFODNN7EXAMPLE",
  secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
};
const ROTATED_CREDENTIALS = {
  accessKeyId: "AKIAI44QH8DHBEXAMPLE",
  secretAccessKey: "other-secret",
};
const SIGNING_DATE = new Date("2025-01-01T00:00:00Z");

function fixedDependencies(
  credentials: typeof CREDENTIALS = CREDENTIALS,
  region = REGION,
) {
  return {
    credentialProvider: async () => credentials,
    regionProvider: async () => region,
    signingDate: SIGNING_DATE,
  };
}

async function withoutRegion<T>(callback: () => Promise<T>): Promise<T> {
  // This helper is intentionally opt-in: only tests that exercise region fallback
  // should be isolated from the developer's ambient AWS_REGION configuration.
  const awsRegion = process.env.AWS_REGION;
  const awsDefaultRegion = process.env.AWS_DEFAULT_REGION;
  delete process.env.AWS_REGION;
  delete process.env.AWS_DEFAULT_REGION;
  try {
    return await callback();
  } finally {
    if (awsRegion === undefined) {
      delete process.env.AWS_REGION;
    } else {
      process.env.AWS_REGION = awsRegion;
    }
    if (awsDefaultRegion === undefined) {
      delete process.env.AWS_DEFAULT_REGION;
    } else {
      process.env.AWS_DEFAULT_REGION = awsDefaultRegion;
    }
  }
}

// These known-answer tests pin the complete presigned token, including its
// signature. A structural test could still pass after a signing-host, canonical
// query, encoding, or credential-scope change that makes real authentication fail.
test("generates the exact serverless known-answer token", async () => {
  const token = await generateIamAuthToken(
    { serverlessCacheName: CACHE, userId: USER, region: REGION },
    fixedDependencies(),
  );

  assert.equal(
    token,
    "my-cache/?Action=connect&User=testuser&ResourceType=ServerlessCache" +
      "&X-Amz-Algorithm=AWS4-HMAC-SHA256" +
      "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20250101%2Fus-east-1" +
      "%2Felasticache%2Faws4_request" +
      "&X-Amz-Date=20250101T000000Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host" +
      "&X-Amz-Signature=" +
      "28f349fd92f74e0192de149a74743ae7d087bb136523c342bdd67632a8360023",
  );
});

test("generates the exact replication-group known-answer token", async () => {
  const token = await generateIamAuthToken(
    { replicationGroupId: CACHE, userId: USER, region: REGION },
    fixedDependencies(),
  );

  assert.equal(
    token,
    "my-cache/?Action=connect&User=testuser" +
      "&X-Amz-Algorithm=AWS4-HMAC-SHA256" +
      "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20250101%2Fus-east-1" +
      "%2Felasticache%2Faws4_request" +
      "&X-Amz-Date=20250101T000000Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host" +
      "&X-Amz-Signature=" +
      "cd78a7de74c1bb1c8c685cac428f783b5433ce5fbf569af867570311d6a85797",
  );
  assert.equal(token.includes("ResourceType"), false);
});

test("normalizes the cache name and user id before signing", async () => {
  const auth = new ElastiCacheIAMAuthTokenProvider(
    {
      serverlessCacheName: "My-Cache",
      userId: "MixedCaseUser",
      region: REGION,
    },
    fixedDependencies(),
  );

  assert.equal(auth.userId, "mixedcaseuser");
  const token = await auth.getToken();
  assert.equal(token.startsWith("my-cache/"), true);
  assert.equal(token.includes("User=mixedcaseuser"), true);
  assert.equal(token.includes("MixedCaseUser"), false);
});

test("accepts service-managed default users", async () => {
  const token = await generateIamAuthToken(
    { serverlessCacheName: CACHE, userId: "DEFAULT.IAM-USER", region: REGION },
    fixedDependencies(),
  );
  assert.equal(token.includes("User=default.iam-user"), true);
});

test("requires exactly one target", async () => {
  await assert.rejects(
    () => generateIamAuthToken({ userId: USER, region: REGION }, fixedDependencies()),
    InvalidParameterError,
  );
  await assert.rejects(
    () =>
      generateIamAuthToken(
        {
          userId: USER,
          region: REGION,
          serverlessCacheName: CACHE,
          replicationGroupId: CACHE,
        },
        fixedDependencies(),
      ),
    InvalidParameterError,
  );
});

test("rejects malformed cache names without echoing input", async () => {
  const hostile = "evil.com/inject\nlog-entry";
  await assert.rejects(
    () =>
      generateIamAuthToken(
        { serverlessCacheName: hostile, userId: USER, region: REGION },
        fixedDependencies(),
      ),
    (error: unknown) => {
      assert.equal(error instanceof InvalidParameterError, true);
      assert.equal((error as Error).message.includes("serverlessCacheName"), true);
      assert.equal((error as Error).message.includes(hostile), false);
      return true;
    },
  );
});

test("rejects malformed user ids", async () => {
  await assert.rejects(
    () =>
      generateIamAuthToken(
        { serverlessCacheName: CACHE, userId: "user.name", region: REGION },
        fixedDependencies(),
      ),
    InvalidParameterError,
  );
});

test("configuration errors are toolkit errors but not parameter errors", async () => {
  assert.equal(ConfigurationError.prototype instanceof ToolkitInputError, true);
  assert.equal(ConfigurationError.prototype instanceof InvalidParameterError, false);
  await assert.rejects(
    () =>
      generateIamAuthToken(
        { serverlessCacheName: CACHE, userId: USER, region: REGION },
        {
          credentialProvider: async () => {
            throw new Error("credential chain failed");
          },
          signingDate: SIGNING_DATE,
        },
      ),
    ConfigurationError,
  );
});

test("normalizes rejected async credential providers", async () => {
  const providerFailure = new Error("secret credential detail\nmust not escape");

  await assert.rejects(
    () =>
      generateIamAuthToken(
        { serverlessCacheName: CACHE, userId: USER, region: REGION },
        {
          credentialProvider: async () => {
            throw providerFailure;
          },
          signingDate: SIGNING_DATE,
        },
      ),
    (error: unknown) => {
      assert.equal(error instanceof ConfigurationError, true);
      assert.equal(
        (error as Error).message,
        "No AWS credentials found. Configure credentials via the environment, " +
          "shared config/credentials files, or an instance/container role.",
      );
      assert.equal((error as Error).message.includes("secret credential detail"), false);
      return true;
    },
  );
});

test("normalizes unusable async credential results", async () => {
  await assert.rejects(
    () =>
      generateIamAuthToken(
        { serverlessCacheName: CACHE, userId: USER, region: REGION },
        {
          credentialProvider: async () => ({
            accessKeyId: "",
            secretAccessKey: "not-a-usable-key",
          }),
          signingDate: SIGNING_DATE,
        },
      ),
    ConfigurationError,
  );
});

test("resolves explicit region before environment and provider", async () => {
  const oldRegion = process.env.AWS_REGION;
  process.env.AWS_REGION = "us-west-2";
  try {
    const token = await generateIamAuthToken(
      { serverlessCacheName: CACHE, userId: USER, region: REGION },
      {
        ...fixedDependencies(),
        regionProvider: async () => SESSION_REGION,
      },
    );
    assert.equal(token.includes("%2Fus-east-1%2F"), true);
    assert.equal(token.includes("%2Feu-west-1%2F"), false);
  } finally {
    if (oldRegion === undefined) {
      delete process.env.AWS_REGION;
    } else {
      process.env.AWS_REGION = oldRegion;
    }
  }
});

test("resolves AWS_REGION before AWS_DEFAULT_REGION and shared config", async () => {
  const oldRegion = process.env.AWS_REGION;
  const oldDefaultRegion = process.env.AWS_DEFAULT_REGION;
  process.env.AWS_REGION = "us-west-2";
  process.env.AWS_DEFAULT_REGION = "ap-southeast-1";
  try {
    const token = await generateIamAuthToken(
      { serverlessCacheName: CACHE, userId: USER },
      {
        credentialProvider: async () => CREDENTIALS,
        regionProvider: async () => SESSION_REGION,
        signingDate: SIGNING_DATE,
      },
    );
    assert.equal(token.includes("%2Fus-west-2%2F"), true);
    assert.equal(token.includes("%2Fap-southeast-1%2F"), false);
    assert.equal(token.includes("%2Feu-west-1%2F"), false);
  } finally {
    if (oldRegion === undefined) {
      delete process.env.AWS_REGION;
    } else {
      process.env.AWS_REGION = oldRegion;
    }
    if (oldDefaultRegion === undefined) {
      delete process.env.AWS_DEFAULT_REGION;
    } else {
      process.env.AWS_DEFAULT_REGION = oldDefaultRegion;
    }
  }
});

test("resolves AWS_DEFAULT_REGION when AWS_REGION is absent", async () => {
  await withoutRegion(async () => {
    process.env.AWS_DEFAULT_REGION = "ap-southeast-1";
    const token = await generateIamAuthToken(
      { serverlessCacheName: CACHE, userId: USER },
      {
        ...fixedDependencies(CREDENTIALS, SESSION_REGION),
        regionProvider: async () => "ap-northeast-1",
      },
    );
    assert.equal(token.includes("%2Fap-southeast-1%2F"), true);
    assert.equal(token.includes("%2Fap-northeast-1%2F"), false);
    assert.equal(token.includes("%2Feu-west-1%2F"), false);
  });
});

test("falls back to the injected shared region provider", async () => {
  await withoutRegion(async () => {
    const token = await generateIamAuthToken(
      { serverlessCacheName: CACHE, userId: USER },
      fixedDependencies(CREDENTIALS, SESSION_REGION),
    );
    assert.equal(token.includes("%2Feu-west-1%2F"), true);
  });
});

test("normalizes rejected region providers", async () => {
  await withoutRegion(async () => {
    await assert.rejects(
      () =>
        generateIamAuthToken(
          { serverlessCacheName: CACHE, userId: USER },
          {
            credentialProvider: async () => CREDENTIALS,
            regionProvider: async () => {
              throw new Error("private config path\nmust not escape");
            },
          },
        ),
      (error: unknown) => {
        assert.equal(error instanceof ConfigurationError, true);
        assert.equal(
          (error as Error).message,
          "No AWS region found. Pass region explicitly, or configure one via " +
            "AWS_REGION, AWS_DEFAULT_REGION, or the region setting in your AWS config profile.",
        );
        assert.equal((error as Error).message.includes("private config path"), false);
        return true;
      },
    );
  });
});

test("raises a configuration error when no region is available", async () => {
  await withoutRegion(async () => {
    await assert.rejects(
      () =>
        generateIamAuthToken(
          { serverlessCacheName: CACHE, userId: USER },
          {
            credentialProvider: async () => CREDENTIALS,
            regionProvider: async () => undefined,
          },
        ),
      (error: unknown) => {
        assert.equal(error instanceof ConfigurationError, true);
        assert.equal((error as Error).message.includes("AWS_REGION"), true);
        assert.equal((error as Error).message.includes("AWS_DEFAULT_REGION"), true);
        return true;
      },
    );
  });
});

test("provider resolves credentials for every token request", async () => {
  // Credentials are deliberately not cached: refreshed SSO, assume-role,
  // container, and instance-metadata credentials must be used on the next request.
  let calls = 0;
  const auth = new ElastiCacheIAMAuthTokenProvider(
    { serverlessCacheName: CACHE, userId: USER, region: REGION },
    {
      credentialProvider: async () => {
        calls++;
        return calls === 1 ? CREDENTIALS : ROTATED_CREDENTIALS;
      },
      signingDate: SIGNING_DATE,
    },
  );

  const first = await auth.getToken();
  const second = await auth.getToken();
  assert.equal(calls, 2);
  assert.notEqual(first, second);
});

test("provider retains its construction-time region while credentials rotate", async () => {
  // The provider refreshes credentials but keeps the region used to construct it;
  // changing the region source later must not change the signing scope.
  await withoutRegion(async () => {
    let regionSource = REGION;
    let regionCalls = 0;
    let credentialCalls = 0;
    const auth = new ElastiCacheIAMAuthTokenProvider(
      { serverlessCacheName: CACHE, userId: USER },
      {
        credentialProvider: async () => {
          credentialCalls++;
          return credentialCalls === 1 ? CREDENTIALS : ROTATED_CREDENTIALS;
        },
        regionProvider: async () => {
          regionCalls++;
          return regionSource;
        },
        signingDate: SIGNING_DATE,
      },
    );

    regionSource = SESSION_REGION;
    const first = await auth.getToken();
    regionSource = "ap-southeast-1";
    const second = await auth.getToken();

    assert.equal(regionCalls, 1);
    assert.equal(credentialCalls, 2);
    assert.equal(first.includes("%2Fus-east-1%2F"), true);
    assert.equal(first.includes("%2Feu-west-1%2F"), false);
    assert.equal(second.includes("%2Fus-east-1%2F"), true);
    assert.equal(second.includes("%2Fap-southeast-1%2F"), false);
  });
});

test("provider reports async region configuration errors through getToken", async () => {
  await withoutRegion(async () => {
    const auth = new ElastiCacheIAMAuthTokenProvider(
      { serverlessCacheName: CACHE, userId: USER },
      {
        credentialProvider: async () => CREDENTIALS,
        regionProvider: async () => undefined,
      },
    );

    await assert.rejects(() => auth.getToken(), ConfigurationError);
  });
});

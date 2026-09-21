// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import assert from "node:assert/strict";
import { test } from "node:test";

import {
  ElastiCacheIAMAuthTokenManager,
  InvalidParameterError,
  TokenRefreshError,
} from "../src/index.js";
import type { TokenManagerScheduler, TokenManagerTimer } from "../src/index.js";

const CACHE = "my-cache";
const USER = "testuser";
const REGION = "us-east-1";
const CREDENTIALS = {
  accessKeyId: "AKIAIOSFODNN7EXAMPLE",
  secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
};
const ROTATED_CREDENTIALS = {
  accessKeyId: "AKIAI44QH8DHBEXAMPLE",
  secretAccessKey: "other-secret",
};
const SIGNING_DATE = new Date("2025-01-01T00:00:00Z");

function flush(): Promise<void> {
  return new Promise((resolve) => setImmediate(resolve));
}

class FakeTimer implements TokenManagerTimer {
  unrefCount = 0;
  cancelled = false;

  constructor(
    readonly at: number,
    readonly delayMs: number,
    readonly callback: () => void,
  ) {}

  unref(): void {
    this.unrefCount++;
  }
}

class TestClock {
  time = 0;
  created: FakeTimer[] = [];
  private pending: FakeTimer[] = [];

  readonly now = (): number => this.time;

  readonly scheduler: TokenManagerScheduler = {
    setTimer: (callback, delayMs) => {
      const timer = new FakeTimer(this.time + delayMs, delayMs, callback);
      this.created.push(timer);
      this.pending.push(timer);
      return timer;
    },
    clearTimer: (timer) => {
      const fake = timer as FakeTimer;
      fake.cancelled = true;
      this.pending = this.pending.filter((candidate) => candidate !== fake);
    },
  };

  async advance(milliseconds: number): Promise<void> {
    const target = this.time + milliseconds;
    while (true) {
      const due = this.pending
        .filter((timer) => !timer.cancelled && timer.at <= target)
        .sort((left, right) => left.at - right.at)[0];
      if (!due) {
        break;
      }
      this.pending = this.pending.filter((timer) => timer !== due);
      this.time = due.at;
      due.callback();
      await flush();
    }
    this.time = target;
    await flush();
  }
}

type Harness = {
  clock: TestClock;
  manager: ElastiCacheIAMAuthTokenManager;
  credentialCalls: () => number;
  changedTokens: string[];
  fail: () => void;
  succeed: () => void;
};

function harness(
  overrides: {
    refreshAfterSeconds?: number;
    onTokenChanged?: (token: string) => void;
    userId?: string;
  } = {},
): Harness {
  const clock = new TestClock();
  let calls = 0;
  let failing = false;
  const changedTokens: string[] = [];

  const manager = new ElastiCacheIAMAuthTokenManager(
    {
      serverlessCacheName: CACHE,
      userId: overrides.userId ?? USER,
      region: REGION,
      refreshAfterSeconds: overrides.refreshAfterSeconds,
      onTokenChanged: overrides.onTokenChanged ?? ((token) => changedTokens.push(token)),
    },
    {
      credentialProvider: async () => {
        calls++;
        if (failing) {
          throw new Error("credential chain failed");
        }
        return calls === 1 ? CREDENTIALS : ROTATED_CREDENTIALS;
      },
      signingDate: SIGNING_DATE,
      now: clock.now,
      random: () => 0.5,
      scheduler: clock.scheduler,
    },
  );

  return {
    clock,
    manager,
    credentialCalls: () => calls,
    changedTokens,
    fail: () => {
      failing = true;
    },
    succeed: () => {
      failing = false;
    },
  };
}

test("caches a token until its background refresh", async () => {
  const { clock, manager, credentialCalls } = harness();
  try {
    const first = await manager.getToken();
    const second = await manager.getToken();

    assert.equal(second, first);
    assert.equal(credentialCalls(), 1);
    assert.equal(clock.created[0]?.delayMs, 300_000);
    assert.equal(clock.created[0]?.unrefCount, 1);
  } finally {
    manager.close();
  }
});

test("refreshes in the background and installs credentials that rotated", async () => {
  const { clock, manager, credentialCalls, changedTokens } = harness();
  try {
    const first = await manager.getToken();
    await clock.advance(300_000);
    const second = await manager.getToken();

    assert.equal(credentialCalls(), 2);
    assert.notEqual(second, first);
    assert.deepEqual(changedTokens, [first, second]);
  } finally {
    manager.close();
  }
});

test("honours a custom refresh interval", async () => {
  const { clock, manager, credentialCalls } = harness({ refreshAfterSeconds: 30 });
  try {
    await manager.getToken();
    await clock.advance(29_999);
    assert.equal(credentialCalls(), 1);
    await clock.advance(1);
    assert.equal(credentialCalls(), 2);
  } finally {
    manager.close();
  }
});

test("shares one initial token mint across concurrent callers", async () => {
  const { manager, credentialCalls } = harness();
  try {
    const [first, second, third] = await Promise.all([
      manager.getToken(),
      manager.getToken(),
      manager.getToken(),
    ]);

    assert.equal(first, second);
    assert.equal(second, third);
    assert.equal(credentialCalls(), 1);
  } finally {
    manager.close();
  }
});

test("returns the user id and cached token from the credential-provider hook", async () => {
  const { manager, credentialCalls } = harness({ userId: "TestUser" });
  try {
    const [userId, token] = await manager.getCredentials();

    assert.equal(userId, USER);
    assert.equal(token, await manager.getToken());
    assert.equal(credentialCalls(), 1);
  } finally {
    manager.close();
  }
});

test("retries refresh failures with capped exponential backoff", async () => {
  const { clock, manager, credentialCalls, fail } = harness();
  fail();

  const result = manager.getToken().then(
    () => undefined,
    (error: unknown) => error,
  );
  await flush();
  await clock.advance(12_000);

  assert.equal((await result) instanceof Error, true);
  assert.equal(credentialCalls(), 8);
  assert.deepEqual(
    clock.created.map((timer) => timer.delayMs),
    [100, 200, 400, 800, 1_600, 3_200, 5_000],
  );
  assert.equal(
    clock.created.every((timer) => timer.unrefCount === 1),
    true,
  );
  manager.close();
});

test("keeps serving a valid token while refresh credentials are unavailable", async () => {
  const { clock, manager, credentialCalls, fail, succeed } = harness();
  try {
    const token = await manager.getToken();
    fail();
    await clock.advance(312_000);

    assert.equal(clock.created.at(-1)?.delayMs, 5_000);
    assert.equal(await manager.getToken(), token);
    // The cooldown scheduled above is still pending, so this call must not
    // start a redundant, concurrent refresh cycle of its own.
    assert.equal(credentialCalls(), 9);

    succeed();
    await clock.advance(5_000);
    assert.notEqual(await manager.getToken(), token);
  } finally {
    manager.close();
  }
});

test("throws TokenRefreshError after the cached token expires", async () => {
  const { clock, manager, fail } = harness({ refreshAfterSeconds: 879 });
  try {
    await manager.getToken();
    fail();
    await clock.advance(911_000);

    const result = manager.getToken().then(
      () => undefined,
      (error: unknown) => error,
    );
    await flush();
    await clock.advance(12_000);

    const error = await result;
    assert.equal(error instanceof TokenRefreshError, true);
    assert.equal((error as Error).cause instanceof Error, true);
  } finally {
    manager.close();
  }
});

test("ignores onTokenChanged callback failures", async () => {
  const manager = harness({
    onTokenChanged: () => {
      throw new Error("consumer callback failed");
    },
  }).manager;
  try {
    assert.equal(typeof (await manager.getToken()), "string");
    assert.equal(await manager.getToken(), await manager.getToken());
  } finally {
    manager.close();
  }
});

test("ignores async onTokenChanged callback rejections", async () => {
  const manager = harness({
    onTokenChanged: async () => {
      throw new Error("async consumer callback failed");
    },
  }).manager;
  try {
    assert.equal(typeof (await manager.getToken()), "string");
    await flush();
  } finally {
    manager.close();
  }
});

test("close cancels background work and rejects later requests", async () => {
  const { clock, manager, credentialCalls } = harness();
  await manager.getToken();
  manager.close();
  await clock.advance(300_000);

  assert.equal(credentialCalls(), 1);
  await assert.rejects(() => manager.getToken(), TokenRefreshError);
});

test("close prevents retry when an in-flight mint fails", async () => {
  const clock = new TestClock();
  let rejectCredentials!: (reason: Error) => void;
  const credentials = new Promise<typeof CREDENTIALS>((_resolve, reject) => {
    rejectCredentials = reject;
  });
  const manager = new ElastiCacheIAMAuthTokenManager(
    {
      serverlessCacheName: CACHE,
      userId: USER,
      region: REGION,
    },
    {
      credentialProvider: () => credentials,
      signingDate: SIGNING_DATE,
      now: clock.now,
      random: () => 0.5,
      scheduler: clock.scheduler,
    },
  );
  const result = manager.getToken();
  await flush();
  manager.close();

  const rejection = assert.rejects(result, TokenRefreshError);
  rejectCredentials(new Error("credential chain failed"));
  await rejection;
  assert.equal(clock.created.length, 0);
});

test("close discards a successful in-flight mint", async () => {
  const clock = new TestClock();
  let resolveCredentials!: (credentials: typeof CREDENTIALS) => void;
  const credentials = new Promise<typeof CREDENTIALS>((resolve) => {
    resolveCredentials = resolve;
  });
  const changedTokens: string[] = [];
  const manager = new ElastiCacheIAMAuthTokenManager(
    {
      serverlessCacheName: CACHE,
      userId: USER,
      region: REGION,
      onTokenChanged: (token) => changedTokens.push(token),
    },
    {
      credentialProvider: () => credentials,
      signingDate: SIGNING_DATE,
      now: clock.now,
      random: () => 0.5,
      scheduler: clock.scheduler,
    },
  );
  const result = manager.getToken();
  await flush();
  manager.close();

  const rejection = assert.rejects(result, TokenRefreshError);
  resolveCredentials(CREDENTIALS);
  await rejection;
  assert.deepEqual(changedTokens, []);
  assert.equal(clock.created.length, 0);
});

for (const refreshAfterSeconds of [
  0,
  -1,
  880,
  899,
  900,
  Number.NaN,
  Number.POSITIVE_INFINITY,
]) {
  test(`rejects invalid refreshAfterSeconds ${String(refreshAfterSeconds)}`, () => {
    assert.throws(
      () =>
        new ElastiCacheIAMAuthTokenManager({
          serverlessCacheName: CACHE,
          userId: USER,
          region: REGION,
          refreshAfterSeconds,
        }),
      InvalidParameterError,
    );
  });
}

test("accepts a refreshAfterSeconds just below the effective token lifetime", () => {
  assert.doesNotThrow(() =>
    new ElastiCacheIAMAuthTokenManager({
      serverlessCacheName: CACHE,
      userId: USER,
      region: REGION,
      refreshAfterSeconds: 879,
    }).close(),
  );
});

test("treats a cached token as expired at the 880 second margin, not the raw 900 second lifetime", async () => {
  const { clock, manager, fail } = harness({ refreshAfterSeconds: 879 });
  try {
    await manager.getToken();
    fail();
    // The background cycle started at refreshAt (879s) exhausts its 8 attempts
    // shortly after 890s: past the 880s effective expiry, but short of the raw
    // 900s token lifetime. Under the raw lifetime this would still be "valid"
    // and the cycle would fall back to serving the stale token instead.
    await clock.advance(891_000);

    const result = manager.getToken().then(
      () => undefined,
      (error: unknown) => error,
    );
    await flush();
    await clock.advance(12_000);

    assert.ok((await result) instanceof TokenRefreshError);
  } finally {
    manager.close();
  }
});

test("does not start a second refresh cycle while one is scheduled during cooldown", async () => {
  const { clock, manager, credentialCalls, fail, succeed } = harness();
  try {
    const token = await manager.getToken();
    fail();
    // Run the first refresh cycle to exhaustion; it falls back to serving the
    // still-valid cached token and schedules a retry after MAX_DELAY_MS.
    await clock.advance(311_300);
    const callsAfterFirstCycle = credentialCalls();
    assert.equal(clock.created.at(-1)?.delayMs, 5_000);

    // Many concurrent reconnect-storm callers hit the cooldown window before
    // the scheduled retry fires. None of them should start another cycle.
    const results = await Promise.all(
      Array.from({ length: 20 }, () => manager.getToken()),
    );
    assert.equal(
      results.every((result) => result === token),
      true,
    );
    assert.equal(credentialCalls(), callsAfterFirstCycle);

    succeed();
    await clock.advance(5_000);
    assert.notEqual(await manager.getToken(), token);
  } finally {
    manager.close();
  }
});

test("refreshToken forces a new token ahead of the scheduled refresh", async () => {
  const { manager, credentialCalls, changedTokens } = harness();
  try {
    const first = await manager.getToken();
    const second = await manager.refreshToken();

    assert.notEqual(second, first);
    assert.equal(await manager.getToken(), second);
    assert.equal(credentialCalls(), 2);
    assert.deepEqual(changedTokens, [first, second]);
  } finally {
    manager.close();
  }
});

test("refreshToken shares one mint across concurrent callers", async () => {
  const { manager, credentialCalls } = harness();
  try {
    await manager.getToken();
    const [first, second, third] = await Promise.all([
      manager.refreshToken(),
      manager.refreshToken(),
      manager.refreshToken(),
    ]);

    assert.equal(first, second);
    assert.equal(second, third);
    assert.equal(credentialCalls(), 2);
  } finally {
    manager.close();
  }
});

test("refreshToken joins an already in-flight background refresh", async () => {
  const { clock, manager, credentialCalls } = harness();
  try {
    const first = await manager.getToken();
    // Cross refreshAt without going through clock.advance()'s timer-firing loop,
    // so the background cycle it starts is still in flight below.
    clock.time = 300_000;
    const background = manager.getToken();
    const forced = manager.refreshToken();
    const [backgroundToken, forcedToken] = await Promise.all([background, forced]);

    assert.equal(backgroundToken, first);
    assert.notEqual(forcedToken, first);
    assert.equal(credentialCalls(), 2);
    assert.equal(await manager.getToken(), forcedToken);
  } finally {
    manager.close();
  }
});

test("refreshToken invalidates the cache while joining a failing background refresh", async () => {
  const { clock, manager, credentialCalls, fail } = harness();
  try {
    const token = await manager.getToken();
    fail();
    clock.time = 300_000;

    const backgroundToken = manager.getToken();
    const forcedResult = manager.refreshToken().then(
      () => undefined,
      (error: unknown) => error,
    );
    const concurrentResult = manager.getToken().then(
      () => undefined,
      (error: unknown) => error,
    );

    assert.equal(await backgroundToken, token);
    await flush();
    await clock.advance(12_000);

    assert.ok((await forcedResult) instanceof Error);
    assert.ok((await concurrentResult) instanceof Error);
    assert.equal(credentialCalls(), 9);
  } finally {
    manager.close();
  }
});

test("refreshToken rejects without a cached token when every attempt fails", async () => {
  const { clock, manager, fail } = harness();
  fail();

  const result = manager.refreshToken().then(
    () => undefined,
    (error: unknown) => error,
  );
  await flush();
  await clock.advance(12_000);

  assert.equal((await result) instanceof Error, true);
  manager.close();
});

test("refreshToken rejects after close", async () => {
  const { manager } = harness();
  manager.close();
  await assert.rejects(() => manager.refreshToken(), TokenRefreshError);
});

test("close aborts an in-flight refreshToken call", async () => {
  const clock = new TestClock();
  let resolveCredentials!: (credentials: typeof CREDENTIALS) => void;
  const credentials = new Promise<typeof CREDENTIALS>((resolve) => {
    resolveCredentials = resolve;
  });
  const manager = new ElastiCacheIAMAuthTokenManager(
    {
      serverlessCacheName: CACHE,
      userId: USER,
      region: REGION,
    },
    {
      credentialProvider: () => credentials,
      signingDate: SIGNING_DATE,
      now: clock.now,
      random: () => 0.5,
      scheduler: clock.scheduler,
    },
  );
  const result = manager.refreshToken();
  await flush();
  manager.close();

  const rejection = assert.rejects(result, TokenRefreshError);
  resolveCredentials(CREDENTIALS);
  await rejection;
});

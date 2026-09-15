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
  } = {},
): Harness {
  const clock = new TestClock();
  let calls = 0;
  let failing = false;
  const changedTokens: string[] = [];

  const manager = new ElastiCacheIAMAuthTokenManager(
    {
      serverlessCacheName: CACHE,
      userId: USER,
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
    assert.equal(credentialCalls(), 10);

    succeed();
    await clock.advance(5_000);
    assert.notEqual(await manager.getToken(), token);
  } finally {
    manager.close();
  }
});

test("throws TokenRefreshError after the cached token expires", async () => {
  const { clock, manager, fail } = harness({ refreshAfterSeconds: 899 });
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

test("close cancels background work and rejects later requests", async () => {
  const { clock, manager, credentialCalls } = harness();
  await manager.getToken();
  manager.close();
  await clock.advance(300_000);

  assert.equal(credentialCalls(), 1);
  await assert.rejects(() => manager.getToken(), TokenRefreshError);
});

for (const refreshAfterSeconds of [0, -1, 900, Number.NaN, Number.POSITIVE_INFINITY]) {
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

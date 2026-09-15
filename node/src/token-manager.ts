// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import { InvalidParameterError, TokenRefreshError } from "./errors.js";
import { ElastiCacheIAMAuthTokenProvider, TOKEN_TTL_SECONDS } from "./token-generator.js";
import type {
  TokenGeneratorDependencies,
  TokenGeneratorOptions,
} from "./token-generator.js";

const DEFAULT_REFRESH_AFTER_SECONDS = 300;
const MAX_ATTEMPTS = 8;
const BASE_DELAY_MS = 100;
const MAX_DELAY_MS = 5_000;
const JITTER_RATIO = 0.2;

const CLOSED_MESSAGE = "The token manager is closed.";
const REFRESH_FAILED_MESSAGE =
  "Could not refresh the ElastiCache IAM authentication token before the " +
  "previous token expired.";

/** A cancellable timer that can be detached from the event loop. */
export interface TokenManagerTimer {
  unref(): void;
}

/** Timer source, injectable so tests can advance time deterministically. */
export interface TokenManagerScheduler {
  setTimer(callback: () => void, delayMs: number): TokenManagerTimer;
  clearTimer(timer: TokenManagerTimer): void;
}

export interface TokenManagerOptions extends TokenGeneratorOptions {
  /**
   * Seconds after issuance at which a token is refreshed in the background.
   * Must be greater than 0 and less than the 900 second token lifetime.
   * Defaults to 300.
   */
  refreshAfterSeconds?: number;
  /**
   * Called with each newly installed token. A callback failure is ignored: it
   * must not discard a token that was minted successfully.
   */
  onTokenChanged?: (token: string) => void;
}

/**
 * Advanced dependencies for deterministic tests and applications with an
 * explicitly managed AWS configuration.
 */
export interface TokenManagerDependencies extends TokenGeneratorDependencies {
  /** Monotonic-enough clock in milliseconds. Defaults to `Date.now`. */
  now?: () => number;
  /** Jitter source in [0, 1). Defaults to `Math.random`. */
  random?: () => number;
  scheduler?: TokenManagerScheduler;
}

type CachedToken = {
  token: string;
  issuedAt: number;
  refreshAt: number;
  expiresAt: number;
};

const defaultScheduler: TokenManagerScheduler = {
  setTimer(callback, delayMs) {
    return setTimeout(callback, delayMs);
  },
  clearTimer(timer) {
    clearTimeout(timer as ReturnType<typeof setTimeout>);
  },
};

function resolveRefreshAfterSeconds(value: unknown): number {
  const resolved = value ?? DEFAULT_REFRESH_AFTER_SECONDS;
  if (
    typeof resolved !== "number" ||
    !Number.isFinite(resolved) ||
    resolved <= 0 ||
    resolved >= TOKEN_TTL_SECONDS
  ) {
    throw new InvalidParameterError(
      "Invalid value for parameter 'refreshAfterSeconds': must be greater than 0 " +
        `and less than ${TOKEN_TTL_SECONDS}.`,
    );
  }
  return resolved;
}

/**
 * Caches an ElastiCache IAM authentication token and refreshes it before expiry.
 *
 * The first token is minted lazily. Subsequent calls reuse it while valid, and
 * concurrent callers share one refresh. Call `close()` when the manager is no
 * longer needed.
 */
export class ElastiCacheIAMAuthTokenManager {
  private readonly provider: ElastiCacheIAMAuthTokenProvider;
  private readonly refreshAfterMs: number;
  private readonly onTokenChanged?: (token: string) => void;
  private readonly now: () => number;
  private readonly random: () => number;
  private readonly scheduler: TokenManagerScheduler;

  private cached?: CachedToken;
  private inFlight?: Promise<string>;
  private refreshTimer?: TokenManagerTimer;
  private retryTimer?: TokenManagerTimer;
  private cancelSleep?: () => void;
  private closed = false;

  constructor(options: TokenManagerOptions, dependencies: TokenManagerDependencies = {}) {
    this.provider = new ElastiCacheIAMAuthTokenProvider(options, dependencies);
    this.refreshAfterMs = resolveRefreshAfterSeconds(options.refreshAfterSeconds) * 1000;
    this.onTokenChanged = options.onTokenChanged;
    this.now = dependencies.now ?? Date.now;
    this.random = dependencies.random ?? Math.random;
    this.scheduler = dependencies.scheduler ?? defaultScheduler;
  }

  /**
   * Create a manager, resolving the region before returning.
   *
   * @throws {InvalidParameterError} for an invalid target, user id, or refresh interval.
   * @throws {ConfigurationError} when no region can be resolved.
   */
  static async create(
    options: TokenManagerOptions,
    dependencies: TokenManagerDependencies = {},
  ): Promise<ElastiCacheIAMAuthTokenManager> {
    const manager = new ElastiCacheIAMAuthTokenManager(options, dependencies);
    await manager.provider.ensureRegionResolved();
    return manager;
  }

  get userId(): string {
    return this.provider.userId;
  }

  /**
   * Return a valid token, minting one on the first call.
   *
   * While a cached token remains valid it is returned immediately. Once its
   * refresh point is reached, refresh proceeds in the background.
   *
   * @throws {TokenRefreshError} when a refresh could not replace a token that has
   * since expired, or when the manager is closed.
   */
  async getToken(): Promise<string> {
    if (this.closed) {
      throw new TokenRefreshError(CLOSED_MESSAGE);
    }

    const cached = this.cached;
    if (cached && this.now() < cached.expiresAt) {
      if (this.now() >= cached.refreshAt) {
        this.startBackgroundRefresh();
      }
      return cached.token;
    }
    return this.refresh();
  }

  /**
   * Stop all background work and release the manager's timers.
   *
   * Subsequent `getToken()` calls reject; a refresh already in flight stops at
   * its next attempt.
   */
  close(): void {
    this.closed = true;
    this.clearRefreshTimer();
    this.clearRetryTimer();
    const cancelSleep = this.cancelSleep;
    this.cancelSleep = undefined;
    cancelSleep?.();
  }

  private refresh(): Promise<string> {
    if (!this.inFlight) {
      const cycle = this.runRefreshCycle();
      this.inFlight = cycle;
      const clear = () => {
        if (this.inFlight === cycle) {
          this.inFlight = undefined;
        }
      };
      cycle.then(clear, clear); // clear no matter the outcome
    }
    return this.inFlight;
  }

  private startBackgroundRefresh(): void {
    void this.refresh().catch(() => {});
  }

  private async runRefreshCycle(): Promise<string> {
    let lastError: unknown = new TokenRefreshError(CLOSED_MESSAGE);

    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      if (this.closed) {
        throw lastError;
      }
      try {
        const issuedAt = this.now();
        const token = await this.provider.getToken();
        this.install(token, issuedAt);
        return token;
      } catch (error) {
        lastError = error;
        if (attempt === MAX_ATTEMPTS) {
          break;
        }
        await this.sleep(this.backoffDelay(attempt));
      }
    }

    const cached = this.cached;
    
    // If a cached token is still valid, return it and schedule another refresh. If
    // there is no cached token, throw the last error. If there is a cached token
    // but it has expired, throw a TokenRefreshError with the last error as its
    // cause.
    if (cached && this.now() < cached.expiresAt) {
      this.scheduleRefresh(MAX_DELAY_MS);
      return cached.token;
    }
    if (!cached) {
      throw lastError;
    }
    throw new TokenRefreshError(REFRESH_FAILED_MESSAGE, { cause: lastError });
  }

  private install(token: string, issuedAt: number): void {
    this.cached = {
      token,
      issuedAt,
      refreshAt: issuedAt + this.refreshAfterMs,
      expiresAt: issuedAt + TOKEN_TTL_SECONDS * 1000,
    };
    this.scheduleRefresh(this.refreshAfterMs);
    this.notify(token);
  }

  private notify(token: string): void {
    if (!this.onTokenChanged) {
      return;
    }
    try {
      this.onTokenChanged(token);
    } catch {
      // A consumer's callback failure must not invalidate a token that was
      // minted successfully, and must not trigger another mint.
    }
  }

  private scheduleRefresh(delayMs: number): void {
    this.clearRefreshTimer();
    if (this.closed) {
      return;
    }
    const timer = this.scheduler.setTimer(() => {
      this.refreshTimer = undefined;
      this.startBackgroundRefresh();
    }, delayMs);
    timer.unref();
    this.refreshTimer = timer;
  }

  private sleep(delayMs: number): Promise<void> {
    return new Promise<void>((resolve) => {
      const finish = () => {
        this.retryTimer = undefined;
        this.cancelSleep = undefined;
        resolve();
      };
      this.cancelSleep = finish;
      const timer = this.scheduler.setTimer(finish, delayMs);
      timer.unref();
      this.retryTimer = timer;
    });
  }

  private backoffDelay(failedAttempt: number): number {
    const delay = Math.min(BASE_DELAY_MS * 2 ** (failedAttempt - 1), MAX_DELAY_MS);
    const jitter = 1 - JITTER_RATIO + 2 * JITTER_RATIO * this.random();
    return Math.round(delay * jitter);
  }

  private clearRefreshTimer(): void {
    if (this.refreshTimer) {
      this.scheduler.clearTimer(this.refreshTimer);
      this.refreshTimer = undefined;
    }
  }

  private clearRetryTimer(): void {
    if (this.retryTimer) {
      this.scheduler.clearTimer(this.retryTimer);
      this.retryTimer = undefined;
    }
  }
}

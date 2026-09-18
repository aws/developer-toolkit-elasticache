/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;

/**
 * Caches an ElastiCache IAM authentication token and refreshes it before expiry.
 *
 * <p>The first token is generated lazily. Calls reuse the cached token while it
 * remains valid, and concurrent callers share a single refresh. Refresh failures
 * are retried in the background while a valid cached token continues to be served.
 *
 * <p>Call {@link #close()} when the manager is no longer needed.
 */
public final class ElastiCacheIamAuthTokenManager implements AutoCloseable {
    private static final Duration DEFAULT_REFRESH_AFTER = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 8;
    private static final long BASE_DELAY_MILLIS = 100;
    private static final long MAX_DELAY_MILLIS = 5_000;
    private static final double JITTER_RATIO = 0.2;
    private static final String CLOSED_MESSAGE = "The token manager is closed.";
    private static final String REFRESH_FAILED_MESSAGE =
            "Could not refresh the ElastiCache IAM authentication token before "
                    + "the previous token expired.";

    private final Object lock = new Object();
    private final ElastiCacheIamAuthTokenProvider provider;
    private final long refreshAfterMillis;
    private final Consumer<String> onTokenChanged;
    private final LongSupplier currentTimeMillis;
    private final DoubleSupplier random;
    private final Scheduler scheduler;
    private final boolean ownsScheduler;

    private CachedToken cached;
    private CompletableFuture<String> inFlight;
    private Cancellable refreshTask;
    private Cancellable retryTask;
    private boolean closed;

    private ElastiCacheIamAuthTokenManager(Builder builder) {
        this.provider = ElastiCacheIamAuthTokenProvider.builder()
                .userId(builder.userId)
                .region(builder.region)
                .serverlessCacheName(builder.serverlessCacheName)
                .replicationGroupId(builder.replicationGroupId)
                .credentialsProvider(builder.credentialsProvider == null
                        ? software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
                                .builder()
                                .build()
                        : builder.credentialsProvider)
                .awsRegionEnvironmentProvider(builder.awsRegionEnvironmentProvider == null
                        ? () -> System.getenv("AWS_REGION")
                        : builder.awsRegionEnvironmentProvider)
                .regionProvider(builder.regionProvider == null
                        ? software.amazon.awssdk.regions.providers
                                .DefaultAwsRegionProviderChain.builder().build()
                        : builder.regionProvider)
                .signingClock(builder.signingClock == null
                        ? Clock.systemUTC()
                        : builder.signingClock)
                .build();
        this.refreshAfterMillis = resolveRefreshAfterMillis(builder.refreshAfter);
        this.onTokenChanged = builder.onTokenChanged;
        this.currentTimeMillis = builder.currentTimeMillis == null
                ? System::currentTimeMillis
                : builder.currentTimeMillis;
        this.random = builder.random == null ? Math::random : builder.random;
        if (builder.scheduler == null) {
            this.scheduler = new DefaultScheduler();
            this.ownsScheduler = true;
        } else {
            this.scheduler = builder.scheduler;
            this.ownsScheduler = false;
        }
    }

    /**
     * Creates a builder for a caching IAM authentication token manager.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the normalized ElastiCache user ID.
     *
     * @return lowercase ElastiCache user ID
     */
    public String getUserId() {
        return provider.getUserId();
    }

    /**
     * Returns the normalized user ID and a valid token together.
     *
     * @return credentials suitable for a client credential-provider adapter
     */
    public Credentials getCredentials() {
        return new Credentials(getUserId(), getToken());
    }

    /**
     * Returns a valid token, generating one on the first call.
     *
     * <p>While a cached token remains valid it is returned immediately. Once its
     * refresh point is reached, refresh proceeds in the background.
     *
     * @return current IAM authentication token
     * @throws TokenRefreshException if the manager is closed or an expired token
     *         could not be replaced
     */
    public String getToken() {
        synchronized (lock) {
            ensureOpen();
            long now = currentTimeMillis.getAsLong();
            if (cached != null && now < cached.expiresAt) {
                String token = cached.token;
                if (now >= cached.refreshAt) {
                    scheduleRefreshLocked(0);
                }
                return token;
            }
        }
        return await(refresh());
    }

    /**
     * Stops all background work and releases the manager's scheduler.
     *
     * <p>Subsequent token requests throw {@link TokenRefreshException}.
     */
    @Override
    public void close() {
        CompletableFuture<String> pending;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            cancel(refreshTask);
            refreshTask = null;
            cancel(retryTask);
            retryTask = null;
            pending = inFlight;
        }
        if (pending != null) {
            pending.completeExceptionally(new TokenRefreshException(CLOSED_MESSAGE));
        }
        if (ownsScheduler) {
            scheduler.close();
        }
    }

    private CompletableFuture<String> refresh() {
        CompletableFuture<String> refresh;
        synchronized (lock) {
            ensureOpen();
            if (inFlight != null) {
                return inFlight;
            }
            refresh = new CompletableFuture<>();
            inFlight = refresh;
            refresh.whenComplete((result, error) -> {
                synchronized (lock) {
                    if (inFlight == refresh) {
                        inFlight = null;
                    }
                }
            });
        }
        runRefreshAttempt(refresh, 1, null);
        return refresh;
    }

    private void startBackgroundRefresh() {
        try {
            refresh();
        } catch (TokenRefreshException ignored) {
            // Closing the manager races safely with a scheduled refresh.
        }
    }

    private void runRefreshAttempt(
            CompletableFuture<String> refresh, int attempt, Throwable lastError) {
        synchronized (lock) {
            if (closed) {
                refresh.completeExceptionally(new TokenRefreshException(
                        CLOSED_MESSAGE, lastError));
                return;
            }
            retryTask = null;
        }

        try {
            long issuedAt = currentTimeMillis.getAsLong();
            String token = provider.getToken();
            synchronized (lock) {
                if (closed) {
                    refresh.completeExceptionally(new TokenRefreshException(CLOSED_MESSAGE));
                    return;
                }
            }
            install(token, issuedAt);
            refresh.complete(token);
        } catch (Throwable failure) {
            if (failure instanceof Error) {
                refresh.completeExceptionally(failure);
                throw (Error) failure;
            }
            RuntimeException exception = failure instanceof RuntimeException
                    ? (RuntimeException) failure
                    : new RuntimeException(failure);
            synchronized (lock) {
                if (closed) {
                    refresh.completeExceptionally(
                            new TokenRefreshException(CLOSED_MESSAGE, exception));
                    return;
                }
            }
            if (attempt == MAX_ATTEMPTS) {
                finishFailedRefresh(refresh, exception);
                return;
            }
            scheduleRetry(refresh, attempt + 1, exception, backoffDelay(attempt));
        }
    }

    private void scheduleRetry(
            CompletableFuture<String> refresh,
            int nextAttempt,
            Throwable lastError,
            long delayMillis) {
        try {
            Cancellable task = scheduler.schedule(
                    () -> runRefreshAttempt(refresh, nextAttempt, lastError), delayMillis);
            synchronized (lock) {
                if (closed) {
                    task.cancel();
                    refresh.completeExceptionally(
                            new TokenRefreshException(CLOSED_MESSAGE, lastError));
                } else {
                    retryTask = task;
                }
            }
        } catch (RejectedExecutionException exception) {
            refresh.completeExceptionally(
                    new TokenRefreshException(CLOSED_MESSAGE, lastError));
        }
    }

    private void finishFailedRefresh(
            CompletableFuture<String> refresh, RuntimeException lastError) {
        String validToken = null;
        synchronized (lock) {
            long now = currentTimeMillis.getAsLong();
            if (cached != null && now < cached.expiresAt) {
                validToken = cached.token;
                scheduleRefreshLocked(MAX_DELAY_MILLIS);
            }
        }
        if (validToken != null) {
            refresh.complete(validToken);
            return;
        }
        synchronized (lock) {
            if (cached == null) {
                refresh.completeExceptionally(lastError);
            } else {
                refresh.completeExceptionally(
                        new TokenRefreshException(REFRESH_FAILED_MESSAGE, lastError));
            }
        }
    }

    private void install(String token, long issuedAt) {
        synchronized (lock) {
            cached = new CachedToken(
                    token,
                    issuedAt + refreshAfterMillis,
                    issuedAt + ElastiCacheIamAuthTokenProvider.TOKEN_TTL.toMillis());
            scheduleRefreshLocked(refreshAfterMillis);
        }
        notifyTokenChanged(token);
    }

    private void notifyTokenChanged(String token) {
        if (onTokenChanged == null) {
            return;
        }
        try {
            onTokenChanged.accept(token);
        } catch (Throwable ignored) {
            // A consumer callback cannot invalidate a successfully signed token.
        }
    }

    private void scheduleRefreshLocked(long delayMillis) {
        cancel(refreshTask);
        refreshTask = null;
        if (closed) {
            return;
        }
        Cancellable[] scheduled = new Cancellable[1];
        scheduled[0] = scheduler.schedule(() -> {
            synchronized (lock) {
                if (refreshTask == scheduled[0]) {
                    refreshTask = null;
                }
            }
            startBackgroundRefresh();
        }, delayMillis);
        refreshTask = scheduled[0];
    }

    private long backoffDelay(int failedAttempt) {
        long exponential = BASE_DELAY_MILLIS << Math.min(failedAttempt - 1, 30);
        long delay = Math.min(exponential, MAX_DELAY_MILLIS);
        double jitter = 1 - JITTER_RATIO + 2 * JITTER_RATIO * random.getAsDouble();
        return Math.round(delay * jitter);
    }

    private void ensureOpen() {
        if (closed) {
            throw new TokenRefreshException(CLOSED_MESSAGE);
        }
    }

    private static void cancel(Cancellable task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static String await(CompletableFuture<String> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw exception;
        }
    }

    private static long resolveRefreshAfterMillis(Duration refreshAfter) {
        Duration resolved = refreshAfter == null ? DEFAULT_REFRESH_AFTER : refreshAfter;
        long millis;
        try {
            millis = resolved.toMillis();
        } catch (ArithmeticException exception) {
            millis = -1;
        }
        if (resolved.isNegative()
                || resolved.isZero()
                || millis <= 0
                || resolved.compareTo(ElastiCacheIamAuthTokenProvider.TOKEN_TTL) >= 0) {
            throw new InvalidParameterException(
                    "Invalid value for parameter 'refreshAfter': must be at least "
                            + "1 millisecond and less than 15 minutes.");
        }
        return millis;
    }

    /**
     * Immutable username and token pair returned by {@link #getCredentials()}.
     */
    public static final class Credentials {
        private final String userId;
        private final String token;

        private Credentials(String userId, String token) {
            this.userId = userId;
            this.token = token;
        }

        /**
         * Returns the normalized ElastiCache user ID.
         *
         * @return lowercase user ID
         */
        public String getUserId() {
            return userId;
        }

        /**
         * Returns the cached IAM authentication token.
         *
         * @return IAM authentication token
         */
        public String getToken() {
            return token;
        }
    }

    /**
     * Builder for {@link ElastiCacheIamAuthTokenManager}.
     */
    public static final class Builder {
        private String userId;
        private Region region;
        private String serverlessCacheName;
        private String replicationGroupId;
        private AwsCredentialsProvider credentialsProvider;
        private Duration refreshAfter;
        private Consumer<String> onTokenChanged;
        private Supplier<String> awsRegionEnvironmentProvider;
        private AwsRegionProvider regionProvider;
        private Clock signingClock;
        private LongSupplier currentTimeMillis;
        private DoubleSupplier random;
        private Scheduler scheduler;

        private Builder() {}

        /**
         * Sets the IAM-enabled ElastiCache user ID.
         *
         * @param userId ElastiCache user ID
         * @return this builder
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Sets the signing region.
         *
         * @param region AWS region
         * @return this builder
         */
        public Builder region(Region region) {
            this.region = region;
            return this;
        }

        /**
         * Sets the serverless cache name.
         *
         * @param serverlessCacheName serverless cache name
         * @return this builder
         */
        public Builder serverlessCacheName(String serverlessCacheName) {
            this.serverlessCacheName = serverlessCacheName;
            return this;
        }

        /**
         * Sets the node-based replication group ID.
         *
         * @param replicationGroupId replication group ID
         * @return this builder
         */
        public Builder replicationGroupId(String replicationGroupId) {
            this.replicationGroupId = replicationGroupId;
            return this;
        }

        /**
         * Sets a credentials provider. The standard AWS provider chain is the default.
         *
         * @param credentialsProvider AWS credentials provider
         * @return this builder
         */
        public Builder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
            this.credentialsProvider = Objects.requireNonNull(
                    credentialsProvider, "credentialsProvider");
            return this;
        }

        /**
         * Sets how long after issuance the token is refreshed in the background.
         *
         * <p>The default is 5 minutes. The value must be at least 1 millisecond
         * and less than the 15-minute token lifetime.
         *
         * @param refreshAfter refresh delay after token issuance
         * @return this builder
         */
        public Builder refreshAfter(Duration refreshAfter) {
            this.refreshAfter = Objects.requireNonNull(refreshAfter, "refreshAfter");
            return this;
        }

        /**
         * Sets a callback invoked after each newly generated token is installed.
         *
         * <p>Callback failures are ignored and do not discard the new token.
         *
         * @param onTokenChanged token-change callback
         * @return this builder
         */
        public Builder onTokenChanged(Consumer<String> onTokenChanged) {
            this.onTokenChanged = Objects.requireNonNull(onTokenChanged, "onTokenChanged");
            return this;
        }

        Builder regionProvider(AwsRegionProvider regionProvider) {
            this.regionProvider = Objects.requireNonNull(regionProvider, "regionProvider");
            return this;
        }

        Builder awsRegionEnvironmentProvider(Supplier<String> provider) {
            this.awsRegionEnvironmentProvider = Objects.requireNonNull(provider, "provider");
            return this;
        }

        Builder signingClock(Clock signingClock) {
            this.signingClock = Objects.requireNonNull(signingClock, "signingClock");
            return this;
        }

        Builder currentTimeMillis(LongSupplier currentTimeMillis) {
            this.currentTimeMillis =
                    Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
            return this;
        }

        Builder random(DoubleSupplier random) {
            this.random = Objects.requireNonNull(random, "random");
            return this;
        }

        Builder scheduler(Scheduler scheduler) {
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            return this;
        }

        /**
         * Validates the configuration and creates the manager.
         *
         * @return configured caching token manager
         */
        public ElastiCacheIamAuthTokenManager build() {
            return new ElastiCacheIamAuthTokenManager(this);
        }
    }

    interface Cancellable {
        void cancel();
    }

    interface Scheduler {
        Cancellable schedule(Runnable task, long delayMillis);

        void close();
    }

    private static final class DefaultScheduler implements Scheduler {
        private final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());

        @Override
        public Cancellable schedule(Runnable task, long delayMillis) {
            ScheduledFuture<?> future =
                    executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "elasticache-iam-token-refresh");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class CachedToken {
        private final String token;
        private final long refreshAt;
        private final long expiresAt;

        private CachedToken(String token, long refreshAt, long expiresAt) {
            this.token = token;
            this.refreshAt = refreshAt;
            this.expiresAt = expiresAt;
        }
    }
}

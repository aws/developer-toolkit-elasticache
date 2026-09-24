/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.regions.Region;

class ElastiCacheIamAuthTokenManagerTest {
    private static final String CACHE = "my-cache";
    private static final String USER = "testuser";
    private static final AwsCredentials FIRST_CREDENTIALS = AwsBasicCredentials.create(
            "AKIAIOSFODNN7EXAMPLE", "first-secret");
    private static final AwsCredentials ROTATED_CREDENTIALS = AwsBasicCredentials.create(
            "AKIAI44QH8DHBEXAMPLE", "second-secret");
    private static final Clock SIGNING_CLOCK =
            Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void cachesTokenUntilBackgroundRefresh() {
        Harness harness = new Harness();
        try {
            String first = harness.manager.getToken();
            String second = harness.manager.getToken();

            assertEquals(first, second);
            assertEquals(1, harness.credentialCalls.get());
            assertEquals(300_000, harness.scheduler.created.get(0).delayMillis);
        } finally {
            harness.manager.close();
        }
    }

    @Test
    void generatesFirstTokenLazily() {
        Harness harness = new Harness();
        try {
            assertEquals(0, harness.credentialCalls.get());
            assertEquals(0, harness.scheduler.createdCount());

            harness.manager.getToken();

            assertEquals(1, harness.credentialCalls.get());
            assertEquals(1, harness.scheduler.createdCount());
        } finally {
            harness.manager.close();
        }
    }

    @Test
    void refreshesInBackgroundWithRotatedCredentials() {
        Harness harness = new Harness();
        try {
            String first = harness.manager.getToken();
            harness.scheduler.advance(300_000);
            String second = harness.manager.getToken();

            assertEquals(2, harness.credentialCalls.get());
            assertNotEquals(first, second);
            assertEquals(2, harness.changedTokens.size());
        } finally {
            harness.manager.close();
        }
    }

    @Test
    void honorsCustomRefreshInterval() {
        Harness harness = new Harness(Duration.ofSeconds(30), null);
        try {
            harness.manager.getToken();
            harness.scheduler.advance(29_999);
            assertEquals(1, harness.credentialCalls.get());
            harness.scheduler.advance(1);
            assertEquals(2, harness.credentialCalls.get());
        } finally {
            harness.manager.close();
        }
    }

    @Test
    void returnsNormalizedCredentialsAndCachedToken() {
        Harness harness = new Harness(null, "TestUser");
        try {
            ElastiCacheIamAuthTokenManager.Credentials credentials =
                    harness.manager.getCredentials();

            assertEquals(USER, credentials.getUserId());
            assertEquals(credentials.getToken(), harness.manager.getToken());
            assertEquals(1, harness.credentialCalls.get());
        } finally {
            harness.manager.close();
        }
    }

    @Test
    void forcedRefreshImmediatelyUsesRotatedCredentialsAndResetsScheduling() {
        Harness harness = new Harness();
        try {
            String first = harness.manager.getToken();
            TestTask originalRefresh = harness.scheduler.lastCreated();

            String replacement = harness.manager.refreshToken();

            assertNotEquals(first, replacement);
            assertEquals(replacement, harness.manager.getToken());
            assertEquals(2, harness.credentialCalls.get());
            assertEquals(java.util.Arrays.asList(first, replacement), harness.changedTokens);
            assertTrue(originalRefresh.cancelled);
            assertEquals(300_000, harness.scheduler.lastCreated().delayMillis);
            harness.scheduler.advance(299_999);
            assertEquals(2, harness.credentialCalls.get());
            harness.scheduler.advance(1);
            assertEquals(3, harness.credentialCalls.get());
        } finally {
            harness.manager.close();
        }
    }

    @Test
    void concurrentForcedRefreshesAndTokenCallersShareReplacement() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger credentialCalls = new AtomicInteger();
        CountDownLatch replacementStarted = new CountDownLatch(1);
        CountDownLatch shared = new CountDownLatch(3);
        CountDownLatch releaseReplacement = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(4);
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    int call = credentialCalls.incrementAndGet();
                    if (call > 1) {
                        replacementStarted.countDown();
                        await(releaseReplacement);
                    }
                    return call == 1 ? FIRST_CREDENTIALS : ROTATED_CREDENTIALS;
                })
                .signingClock(SIGNING_CLOCK)
                .currentTimeMillis(scheduler::now)
                .scheduler(scheduler)
                .onSharedRefresh(shared::countDown)
                .build();
        try {
            String rejected = manager.getToken();
            CompletableFuture<String> first =
                    CompletableFuture.supplyAsync(manager::refreshToken, callers);
            await(replacementStarted);
            CompletableFuture<String> second =
                    CompletableFuture.supplyAsync(manager::refreshToken, callers);
            CompletableFuture<String> token =
                    CompletableFuture.supplyAsync(manager::getToken, callers);
            CompletableFuture<ElastiCacheIamAuthTokenManager.Credentials> credentials =
                    CompletableFuture.supplyAsync(manager::getCredentials, callers);
            await(shared);
            releaseReplacement.countDown();

            String replacement = first.join();
            assertNotEquals(rejected, replacement);
            assertEquals(replacement, second.join());
            assertEquals(replacement, token.join());
            assertEquals(replacement, credentials.join().getToken());
            assertEquals(2, credentialCalls.get());
        } finally {
            releaseReplacement.countDown();
            manager.close();
            callers.shutdownNow();
        }
    }

    @Test
    void forcedRefreshJoinsBackgroundRefreshWithoutStaleOverwrite() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger credentialCalls = new AtomicInteger();
        CountDownLatch backgroundStarted = new CountDownLatch(1);
        CountDownLatch shared = new CountDownLatch(1);
        CountDownLatch releaseBackground = new CountDownLatch(1);
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    int call = credentialCalls.incrementAndGet();
                    if (call > 1) {
                        backgroundStarted.countDown();
                        await(releaseBackground);
                    }
                    return call == 1 ? FIRST_CREDENTIALS : ROTATED_CREDENTIALS;
                })
                .signingClock(SIGNING_CLOCK)
                .currentTimeMillis(scheduler::now)
                .scheduler(scheduler)
                .onSharedRefresh(shared::countDown)
                .build();
        try {
            String rejected = manager.getToken();
            CompletableFuture<Void> background =
                    CompletableFuture.runAsync(() -> scheduler.advance(300_000));
            await(backgroundStarted);
            CompletableFuture<String> forced =
                    CompletableFuture.supplyAsync(manager::refreshToken);
            await(shared);
            releaseBackground.countDown();

            String replacement = forced.join();
            background.join();
            assertNotEquals(rejected, replacement);
            assertEquals(replacement, manager.getToken());
            assertEquals(2, credentialCalls.get());
            assertEquals(2, scheduler.createdCount());
        } finally {
            releaseBackground.countDown();
            manager.close();
        }
    }

    @Test
    void failedForcedRefreshDoesNotReturnInvalidatedToken() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger credentialCalls = new AtomicInteger();
        AtomicBoolean failing = new AtomicBoolean();
        CountDownLatch shared = new CountDownLatch(1);
        ElastiCacheIamAuthTokenManager manager =
                baseBuilder(scheduler, credentialCalls, failing)
                        .onSharedRefresh(shared::countDown)
                        .build();
        try {
            String rejected = manager.getToken();
            failing.set(true);
            CompletableFuture<Throwable> forced = CompletableFuture.supplyAsync(
                    () -> captureFailure(manager::refreshToken));
            awaitCreatedTasks(scheduler, 2);
            CompletableFuture<Throwable> concurrent = CompletableFuture.supplyAsync(
                    () -> captureFailure(manager::getToken));
            await(shared);
            scheduler.advance(12_000);

            Throwable forcedFailure = forced.join();
            Throwable concurrentFailure = concurrent.join();
            assertTrue(forcedFailure instanceof ConfigurationException);
            assertSame(forcedFailure, concurrentFailure);
            assertNotEquals(rejected, forcedFailure.getMessage());
            assertEquals(9, credentialCalls.get());

            failing.set(false);
            assertNotEquals(rejected, manager.getToken());
            assertEquals(10, credentialCalls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void sharesInitialTokenGenerationAcrossConcurrentCallers() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger credentialCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch shared = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    credentialCalls.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to release provider");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return FIRST_CREDENTIALS;
                })
                .signingClock(SIGNING_CLOCK)
                .currentTimeMillis(scheduler::now)
                .scheduler(scheduler)
                .onSharedRefresh(shared::countDown)
                .build();
        try {
            CompletableFuture<String> first =
                    CompletableFuture.supplyAsync(manager::getToken);
            await(entered);
            CompletableFuture<String> second =
                    CompletableFuture.supplyAsync(manager::getToken);
            await(shared);
            release.countDown();

            assertEquals(first.join(), second.join());
            assertEquals(1, credentialCalls.get());
        } finally {
            release.countDown();
            manager.close();
        }
    }

    @Test
    void retriesInitialRefreshWithCappedExponentialBackoff() {
        Harness harness = new Harness();
        harness.failing.set(true);
        CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> {
            try {
                harness.manager.getToken();
                return null;
            } catch (RuntimeException exception) {
                return exception;
            }
        });

        awaitCreatedTasks(harness.scheduler, 1);
        harness.scheduler.advance(12_000);

        assertTrue(result.join() instanceof ConfigurationException);
        assertEquals(8, harness.credentialCalls.get());
        assertEquals(
                java.util.Arrays.asList(100L, 200L, 400L, 800L, 1_600L, 3_200L, 5_000L),
                harness.scheduler.delays());
        harness.manager.close();
    }

    @Test
    void initialErrorClearsInFlightAndAllowsRetry() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger credentialCalls = new AtomicInteger();
        AtomicBoolean throwError = new AtomicBoolean(true);
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    credentialCalls.incrementAndGet();
                    if (throwError.getAndSet(false)) {
                        throw new AssertionError("fatal credential provider failure");
                    }
                    return FIRST_CREDENTIALS;
                })
                .signingClock(SIGNING_CLOCK)
                .currentTimeMillis(scheduler::now)
                .scheduler(scheduler)
                .build();
        try {
            assertThrows(AssertionError.class, manager::getToken);

            assertFalse(manager.getToken().isEmpty());
            assertEquals(2, credentialCalls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void waitingCallerReceivesOriginalErrorFromSharedRefresh() {
        TestScheduler scheduler = new TestScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch shared = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AssertionError failure = new AssertionError("fatal credential provider failure");
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to release provider");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    throw failure;
                })
                .signingClock(SIGNING_CLOCK)
                .currentTimeMillis(scheduler::now)
                .scheduler(scheduler)
                .onSharedRefresh(shared::countDown)
                .build();
        try {
            CompletableFuture<Throwable> first =
                    CompletableFuture.supplyAsync(() -> captureFailure(manager::getToken));
            await(entered);
            CompletableFuture<Throwable> second =
                    CompletableFuture.supplyAsync(() -> captureFailure(manager::getToken));
            await(shared);
            release.countDown();

            assertSame(failure, first.join());
            assertSame(failure, second.join());
        } finally {
            release.countDown();
            manager.close();
        }
    }

    @Test
    void backgroundErrorClearsInFlightAndAllowsLaterRefresh() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger credentialCalls = new AtomicInteger();
        AtomicBoolean throwError = new AtomicBoolean();
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    int call = credentialCalls.incrementAndGet();
                    if (throwError.get()) {
                        throw new AssertionError("fatal credential provider failure");
                    }
                    return call == 1 ? FIRST_CREDENTIALS : ROTATED_CREDENTIALS;
                })
                .signingClock(SIGNING_CLOCK)
                .currentTimeMillis(scheduler::now)
                .scheduler(scheduler)
                .build();
        try {
            String first = manager.getToken();
            throwError.set(true);

            assertThrows(AssertionError.class, () -> scheduler.advance(300_000));

            throwError.set(false);
            assertEquals(first, manager.getToken());
            scheduler.advance(0);
            assertNotEquals(first, manager.getToken());
            assertEquals(3, credentialCalls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void servesValidTokenWhileRefreshFailsAndRecovers() {
        Harness harness = new Harness();
        try {
            String token = harness.manager.getToken();
            harness.failing.set(true);
            harness.scheduler.advance(312_000);

            assertEquals(5_000, harness.scheduler.lastCreated().delayMillis);
            int tasksAfterFailure = harness.scheduler.createdCount();
            assertEquals(token, harness.manager.getToken());
            assertEquals(token, harness.manager.getToken());
            assertEquals(9, harness.credentialCalls.get());
            assertEquals(tasksAfterFailure, harness.scheduler.createdCount());

            harness.failing.set(false);
            harness.scheduler.advance(5_000);
            assertNotEquals(token, harness.manager.getToken());
        } finally {
            harness.manager.close();
        }
    }

    @Test
    void treatsTokenAsExpiredAtEffectiveLifetime() {
        Harness harness = new Harness(Duration.ofSeconds(879), null);
        try {
            harness.manager.getToken();
            harness.failing.set(true);
            // The first failed cycle ends after the 880-second effective expiry
            // but before the raw 900-second token expiry.
            harness.scheduler.advance(891_000);

            CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> {
                try {
                    harness.manager.getToken();
                    return null;
                } catch (RuntimeException exception) {
                    return exception;
                }
            });
            awaitCreatedTasks(harness.scheduler, 9);
            harness.scheduler.advance(12_000);

            Throwable error = result.join();
            assertTrue(error instanceof TokenRefreshException);
            assertTrue(error.getCause() instanceof ConfigurationException);
        } finally {
            harness.manager.close();
        }
    }

    @Test
    void ignoresTokenChangedCallbackFailures() {
        Harness harness = new Harness(
                null,
                null,
                token -> {
                    throw new AssertionError("consumer callback failed");
                });
        try {
            assertFalse(harness.manager.getToken().isEmpty());
            assertEquals(1, harness.credentialCalls.get());
        } finally {
            harness.manager.close();
        }
    }

    @Test
    void blockingTokenChangedCallbackDoesNotDelayTokenCaller() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<String> callbackThread = new AtomicReference<>();
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> FIRST_CREDENTIALS)
                .signingClock(SIGNING_CLOCK)
                .onTokenChanged(token -> {
                    callbackThread.set(Thread.currentThread().getName());
                    callbackEntered.countDown();
                    try {
                        releaseCallback.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                })
                .build();
        try {
            CompletableFuture<String> token =
                    CompletableFuture.supplyAsync(manager::getToken);

            await(callbackEntered);
            assertFalse(token.get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals("elasticache-iam-token-refresh", callbackThread.get());
        } finally {
            releaseCallback.countDown();
            manager.close();
        }
    }

    @Test
    void closeCancelsBackgroundWorkAndRejectsRequests() {
        Harness harness = new Harness();
        harness.manager.getToken();
        harness.manager.close();
        harness.scheduler.advance(300_000);

        assertEquals(1, harness.credentialCalls.get());
        assertThrows(TokenRefreshException.class, harness.manager::getToken);
        assertThrows(TokenRefreshException.class, harness.manager::refreshToken);
    }

    @Test
    void closeCancelsForcedRefreshInFlight() {
        TestScheduler scheduler = new TestScheduler();
        CountDownLatch replacementStarted = new CountDownLatch(1);
        CountDownLatch releaseReplacement = new CountDownLatch(1);
        AtomicInteger credentialCalls = new AtomicInteger();
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    int call = credentialCalls.incrementAndGet();
                    if (call > 1) {
                        replacementStarted.countDown();
                        await(releaseReplacement);
                    }
                    return call == 1 ? FIRST_CREDENTIALS : ROTATED_CREDENTIALS;
                })
                .signingClock(SIGNING_CLOCK)
                .currentTimeMillis(scheduler::now)
                .scheduler(scheduler)
                .build();
        try {
            manager.getToken();
            CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(
                    () -> captureFailure(manager::refreshToken));
            await(replacementStarted);
            manager.close();
            releaseReplacement.countDown();

            Throwable failure = result.join();
            assertTrue(failure instanceof TokenRefreshException);
            assertEquals(
                    "Token refresh was cancelled because the token manager was closed.",
                    failure.getMessage());
            assertEquals(1, scheduler.createdCount());
        } finally {
            releaseReplacement.countDown();
            manager.close();
        }
    }

    @Test
    void closePreventsRetryWhenInFlightGenerationFails() {
        TestScheduler scheduler = new TestScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    entered.countDown();
                    await(release);
                    throw new IllegalStateException("credential chain failed");
                })
                .signingClock(SIGNING_CLOCK)
                .currentTimeMillis(scheduler::now)
                .scheduler(scheduler)
                .build();

        CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> {
            try {
                manager.getToken();
                return null;
            } catch (RuntimeException exception) {
                return exception;
            }
        });
        await(entered);
        manager.close();
        release.countDown();

        Throwable failure = result.join();
        assertTrue(failure instanceof TokenRefreshException);
        assertEquals(
                "Token refresh was cancelled because the token manager was closed.",
                failure.getMessage());
        assertEquals(0, scheduler.createdCount());
    }

    @Test
    void closeAfterSigningPreventsTokenInstallationAndNotification() {
        TestScheduler scheduler = new TestScheduler();
        List<String> changedTokens = new ArrayList<>();
        CountDownLatch signed = new CountDownLatch(1);
        CountDownLatch install = new CountDownLatch(1);
        ElastiCacheIamAuthTokenManager manager =
                baseBuilder(scheduler, new AtomicInteger(), new AtomicBoolean())
                        .onTokenChanged(changedTokens::add)
                        .beforeInstall(() -> {
                            signed.countDown();
                            await(install);
                        })
                        .build();
        CompletableFuture<Throwable> result =
                CompletableFuture.supplyAsync(() -> captureFailure(manager::getToken));

        await(signed);
        manager.close();
        install.countDown();

        Throwable failure = result.join();
        assertTrue(failure instanceof TokenRefreshException);
        assertEquals(
                "Token refresh was cancelled because the token manager was closed.",
                failure.getMessage());
        assertTrue(changedTokens.isEmpty());
        assertEquals(0, scheduler.createdCount());
    }

    @Test
    void validatesRefreshInterval() {
        for (Duration value : java.util.Arrays.asList(
                Duration.ZERO,
                Duration.ofMillis(-1),
                Duration.ofNanos(1),
                Duration.ofSeconds(880),
                Duration.ofSeconds(899),
                Duration.ofMinutes(15),
                Duration.ofMinutes(16))) {
            assertThrows(
                    InvalidParameterException.class,
                    () -> baseBuilder(new TestScheduler(), new AtomicInteger(), new AtomicBoolean())
                            .refreshAfter(value)
                            .build());
        }

        ElastiCacheIamAuthTokenManager manager =
                baseBuilder(new TestScheduler(), new AtomicInteger(), new AtomicBoolean())
                        .refreshAfter(Duration.ofSeconds(879))
                        .build();
        manager.close();
    }

    @Test
    void refreshExceptionIsNotAnInputException() {
        assertFalse(ToolkitInputException.class.isAssignableFrom(TokenRefreshException.class));
    }

    private static ElastiCacheIamAuthTokenManager.Builder baseBuilder(
            TestScheduler scheduler,
            AtomicInteger credentialCalls,
            AtomicBoolean failing) {
        return ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    int call = credentialCalls.incrementAndGet();
                    if (failing.get()) {
                        throw new IllegalStateException("credential chain failed");
                    }
                    return call == 1 ? FIRST_CREDENTIALS : ROTATED_CREDENTIALS;
                })
                .signingClock(SIGNING_CLOCK)
                .currentTimeMillis(scheduler::now)
                .random(() -> 0.5)
                .scheduler(scheduler);
    }

    private static void awaitCreatedTasks(TestScheduler scheduler, int expected) {
        assertTrue(
                scheduler.awaitCreatedCount(expected, 5, TimeUnit.SECONDS),
                "timed out waiting for scheduled tasks");
        assertEquals(expected, scheduler.createdCount());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for latch");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for latch", exception);
        }
    }

    private static Throwable captureFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static final class Harness {
        private final TestScheduler scheduler = new TestScheduler();
        private final AtomicInteger credentialCalls = new AtomicInteger();
        private final AtomicBoolean failing = new AtomicBoolean();
        private final List<String> changedTokens = new ArrayList<>();
        private final ElastiCacheIamAuthTokenManager manager;

        private Harness() {
            this(null, null);
        }

        private Harness(Duration refreshAfter, String userId) {
            this(refreshAfter, userId, null);
        }

        private Harness(
                Duration refreshAfter, String userId, java.util.function.Consumer<String> callback) {
            ElastiCacheIamAuthTokenManager.Builder builder =
                    baseBuilder(scheduler, credentialCalls, failing)
                            .userId(userId == null ? USER : userId)
                            .onTokenChanged(callback == null ? changedTokens::add : callback);
            if (refreshAfter != null) {
                builder.refreshAfter(refreshAfter);
            }
            manager = builder.build();
        }
    }

    private static final class TestScheduler
            implements ElastiCacheIamAuthTokenManager.Scheduler {
        private long time;
        private final List<TestTask> pending = new ArrayList<>();
        private final List<TestTask> created = new ArrayList<>();

        private synchronized long now() {
            return time;
        }

        @Override
        public synchronized ElastiCacheIamAuthTokenManager.Cancellable schedule(
                Runnable task, long delayMillis) {
            TestTask scheduled = new TestTask(time + delayMillis, delayMillis, task);
            pending.add(scheduled);
            created.add(scheduled);
            notifyAll();
            return scheduled;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void close() {}

        private synchronized void advance(long milliseconds) {
            long target = time + milliseconds;
            while (true) {
                TestTask next = pending.stream()
                        .filter(task -> !task.cancelled && task.at <= target)
                        .min(Comparator.comparingLong(task -> task.at))
                        .orElse(null);
                if (next == null) {
                    break;
                }
                pending.remove(next);
                time = next.at;
                next.run();
            }
            time = target;
        }

        private synchronized List<Long> delays() {
            List<Long> delays = new ArrayList<>();
            for (TestTask task : created) {
                delays.add(task.delayMillis);
            }
            return delays;
        }

        private synchronized TestTask lastCreated() {
            return created.get(created.size() - 1);
        }

        private synchronized int createdCount() {
            return created.size();
        }

        private synchronized boolean awaitCreatedCount(
                int expected, long timeout, TimeUnit unit) {
            long remainingNanos = unit.toNanos(timeout);
            long deadline = System.nanoTime() + remainingNanos;
            while (created.size() < expected && remainingNanos > 0) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remainingNanos = deadline - System.nanoTime();
            }
            return created.size() >= expected;
        }
    }

    private static final class TestTask
            implements ElastiCacheIamAuthTokenManager.Cancellable {
        private final long at;
        private final long delayMillis;
        private final Runnable task;
        private boolean cancelled;

        private TestTask(long at, long delayMillis, Runnable task) {
            this.at = at;
            this.delayMillis = delayMillis;
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void run() {
            if (!cancelled) {
                task.run();
            }
        }
    }
}

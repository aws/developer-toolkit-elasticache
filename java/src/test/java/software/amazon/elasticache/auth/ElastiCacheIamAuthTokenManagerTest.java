/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    void sharesInitialTokenGenerationAcrossConcurrentCallers() throws InterruptedException {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger credentialCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    credentialCalls.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return FIRST_CREDENTIALS;
                })
                .signingClock(SIGNING_CLOCK)
                .currentTimeMillis(scheduler::now)
                .scheduler(scheduler)
                .build();
        try {
            CompletableFuture<String> first =
                    CompletableFuture.supplyAsync(manager::getToken);
            entered.await();
            CompletableFuture<String> second =
                    CompletableFuture.supplyAsync(manager::getToken);
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
    void servesValidTokenWhileRefreshFailsAndRecovers() {
        Harness harness = new Harness();
        try {
            String token = harness.manager.getToken();
            harness.failing.set(true);
            harness.scheduler.advance(312_000);

            assertEquals(5_000, harness.scheduler.lastCreated().delayMillis);
            assertEquals(token, harness.manager.getToken());
            assertEquals(9, harness.credentialCalls.get());

            harness.failing.set(false);
            harness.scheduler.advance(0);
            assertNotEquals(token, harness.manager.getToken());
        } finally {
            harness.manager.close();
        }
    }

    @Test
    void throwsRefreshExceptionAfterCachedTokenExpires() {
        Harness harness = new Harness(Duration.ofSeconds(899), null);
        try {
            harness.manager.getToken();
            harness.failing.set(true);
            harness.scheduler.advance(911_000);

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
    void closeCancelsBackgroundWorkAndRejectsRequests() {
        Harness harness = new Harness();
        harness.manager.getToken();
        harness.manager.close();
        harness.scheduler.advance(300_000);

        assertEquals(1, harness.credentialCalls.get());
        assertThrows(TokenRefreshException.class, harness.manager::getToken);
    }

    @Test
    void closePreventsRetryWhenInFlightGenerationFails() throws InterruptedException {
        TestScheduler scheduler = new TestScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ElastiCacheIamAuthTokenManager manager = ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName(CACHE)
                .userId(USER)
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
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
        entered.await();
        manager.close();
        release.countDown();

        assertTrue(result.join() instanceof TokenRefreshException);
        assertEquals(0, scheduler.createdCount());
    }

    @Test
    void validatesRefreshInterval() {
        for (Duration value : java.util.Arrays.asList(
                Duration.ZERO,
                Duration.ofMillis(-1),
                Duration.ofNanos(1),
                Duration.ofMinutes(15),
                Duration.ofMinutes(16))) {
            assertThrows(
                    InvalidParameterException.class,
                    () -> baseBuilder(new TestScheduler(), new AtomicInteger(), new AtomicBoolean())
                            .refreshAfter(value)
                            .build());
        }
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
        for (int attempt = 0; attempt < 10_000
                && scheduler.createdCount() < expected; attempt++) {
            Thread.yield();
        }
        assertEquals(expected, scheduler.createdCount());
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
            return scheduled;
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

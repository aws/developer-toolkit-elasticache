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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;

class ElastiCacheIamAuthTokenProviderTest {
    private static final String CACHE = "my-cache";
    private static final String USER = "testuser";
    private static final Region REGION = Region.US_EAST_1;
    private static final AwsCredentials CREDENTIALS = AwsSessionCredentials.create(
            "FRAIDA1FODNN7EXAMPLE", "secret", "token");

    @Test
    void generatesServerlessToken() {
        String token = providerBuilder().serverlessCacheName(CACHE).build().getToken();

        assertValidToken(token);
        assertTrue(token.contains("ResourceType=ServerlessCache"));
    }

    @Test
    void generatesReplicationGroupToken() {
        String token = providerBuilder().replicationGroupId(CACHE).build().getToken();

        assertValidToken(token);
        assertFalse(token.contains("ResourceType"));
    }

    @Test
    void exposesNormalizedUserIdAndSignsTheSameValue() {
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName("My-Cache")
                .userId("MixedCaseUser")
                .build();

        assertEquals("mixedcaseuser", provider.getUserId());
        String token = provider.getToken();
        assertTrue(token.startsWith("my-cache/"));
        assertTrue(token.contains("User=mixedcaseuser"));
        assertFalse(token.contains("MixedCaseUser"));
    }

    @Test
    void resolvesRegionAtConstruction() {
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .region(null)
                .regionProvider(() -> Region.EU_WEST_1)
                .build();

        assertTrue(provider.getToken().contains("%2Feu-west-1%2F"));
    }

    @Test
    void explicitRegionWinsOverRegionProvider() {
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .regionProvider(() -> Region.EU_WEST_1)
                .build();

        String token = provider.getToken();
        assertTrue(token.contains("%2Fus-east-1%2F"));
        assertFalse(token.contains("eu-west-1"));
    }

    @Test
    void missingRegionFailsAtConstruction() {
        ConfigurationException exception = assertThrows(
                ConfigurationException.class,
                () -> providerBuilder()
                        .serverlessCacheName(CACHE)
                        .region(null)
                        .regionProvider(() -> {
                            throw SdkClientException.create("missing");
                        })
                        .build());

        assertTrue(exception.getMessage().contains("AWS_REGION"));
        assertTrue(exception.getMessage().contains("AWS_DEFAULT_REGION"));
    }

    @Test
    void readsCredentialsForEveryToken() {
        AtomicInteger calls = new AtomicInteger();
        AwsCredentialsProvider credentialsProvider = () -> {
            calls.incrementAndGet();
            return CREDENTIALS;
        };
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .credentialsProvider(credentialsProvider)
                .build();

        provider.getToken();
        provider.getToken();

        assertEquals(2, calls.get());
    }

    @Test
    void rotatedCredentialsChangeTheToken() {
        AtomicInteger calls = new AtomicInteger();
        AwsCredentialsProvider credentialsProvider = () -> calls.getAndIncrement() == 0
                ? AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "first-secret")
                : AwsBasicCredentials.create("AKIAI44QH8DHBEXAMPLE", "second-secret");
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .credentialsProvider(credentialsProvider)
                .build();

        assertNotEquals(provider.getToken(), provider.getToken());
    }

    @Test
    void missingCredentialsHasActionableError() {
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .credentialsProvider(() -> {
                    throw SdkClientException.create("missing");
                })
                .build();

        ConfigurationException exception =
                assertThrows(ConfigurationException.class, provider::getToken);
        assertTrue(exception.getMessage().contains("credentials"));
    }

    @Test
    void requiresExactlyOneTarget() {
        assertThrows(InvalidParameterException.class, () -> providerBuilder().build());
        assertThrows(
                InvalidParameterException.class,
                () -> providerBuilder()
                        .serverlessCacheName(CACHE)
                        .replicationGroupId(CACHE)
                        .build());
    }

    @Test
    void rejectsInvalidCacheNamesWithoutEchoingThem() {
        List<String> badNames = Arrays.asList(
                "evil.com/path",
                "my-cache@evil.com",
                "my-cache/?X=1",
                "my cache",
                "my_cache",
                "1-cache",
                "-cache",
                "cache-",
                "my--cache",
                "-");

        for (String badName : badNames) {
            InvalidParameterException exception = assertThrows(
                    InvalidParameterException.class,
                    () -> providerBuilder().serverlessCacheName(badName).build());
            assertTrue(exception.getMessage().contains("serverless_cache_name"));
        }
    }

    @Test
    void parameterErrorsDoNotEchoHostileValues() {
        String hostile = "evil.com/inject\nlog-entry";
        InvalidParameterException exception = assertThrows(
                InvalidParameterException.class,
                () -> providerBuilder().serverlessCacheName(hostile).build());

        assertTrue(exception.getMessage().contains("serverless_cache_name"));
        assertFalse(exception.getMessage().contains(hostile));
    }

    @Test
    void acceptsValidCacheNames() {
        for (String name : Arrays.asList("c", "my-cache", "cache1", "a-1-b-2", "MyCache")) {
            String token = providerBuilder().serverlessCacheName(name).build().getToken();
            assertTrue(token.startsWith(name.toLowerCase() + "/"));
        }
    }

    @Test
    void validatesUserIds() {
        for (String userId : Arrays.asList(
                "", "1user", "-user", "user name", "user@host", "user.name",
                "default.", "default.1x", "notdefault.foo")) {
            InvalidParameterException exception = assertThrows(
                    InvalidParameterException.class,
                    () -> providerBuilder()
                            .serverlessCacheName(CACHE)
                            .userId(userId)
                            .build());
            assertTrue(exception.getMessage().contains("user_id"));
        }

        for (String userId : Arrays.asList(
                "iam-user", "myuser", "default", "default.iam-user",
                "default.other", "DEFAULT.IAM-USER", "a-1-b-2")) {
            ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                    .serverlessCacheName(CACHE)
                    .userId(userId)
                    .build();
            assertEquals(userId.toLowerCase(), provider.getUserId());
        }
    }

    @Test
    void configurationExceptionIsNotAParameterException() {
        assertFalse(InvalidParameterException.class.isAssignableFrom(ConfigurationException.class));
    }

    private static ElastiCacheIamAuthTokenProvider.Builder providerBuilder() {
        return ElastiCacheIamAuthTokenProvider.builder()
                .userId(USER)
                .region(REGION)
                .credentialsProvider(StaticCredentialsProvider.create(CREDENTIALS));
    }

    private static void assertValidToken(String token) {
        assertTrue(token.startsWith(CACHE + "/"));
        assertTrue(token.contains("Action=connect"));
        assertTrue(token.contains("User=" + USER));
        assertTrue(token.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
        assertTrue(token.contains("X-Amz-Credential"));
        assertTrue(token.contains("X-Amz-Signature"));
        assertTrue(token.contains("X-Amz-Expires=900"));
        assertFalse(token.startsWith("https://"));
    }
}

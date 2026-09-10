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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final Clock KNOWN_ANSWER_CLOCK =
            Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final AwsCredentials KNOWN_ANSWER_CREDENTIALS = AwsBasicCredentials.create(
            "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");

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
                .awsRegionEnvironmentProvider(() -> null)
                .regionProvider(() -> Region.EU_WEST_1)
                .build();

        assertTrue(provider.getToken().contains("%2Feu-west-1%2F"));
    }

    @Test
    void explicitRegionWinsOverRegionProvider() {
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .awsRegionEnvironmentProvider(() -> "ap-southeast-2")
                .regionProvider(() -> Region.EU_WEST_1)
                .build();

        String token = provider.getToken();
        assertTrue(token.contains("%2Fus-east-1%2F"));
        assertFalse(token.contains("eu-west-1"));
    }

    @Test
    void awsRegionEnvironmentWinsOverSdkRegionChain() {
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .region(null)
                .awsRegionEnvironmentProvider(() -> "ap-southeast-2")
                .regionProvider(() -> Region.EU_WEST_1)
                .build();

        String token = provider.getToken();
        assertTrue(token.contains("%2Fap-southeast-2%2F"));
        assertFalse(token.contains("eu-west-1"));
    }

    @Test
    void sdkRegionChainIsUsedWhenAwsRegionIsAbsent() {
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .region(null)
                .awsRegionEnvironmentProvider(() -> null)
                .regionProvider(() -> Region.EU_WEST_1)
                .build();

        String token = provider.getToken();
        assertTrue(token.contains("%2Feu-west-1%2F"));
    }

    @Test
    void missingRegionFailsAtConstruction() {
        String providerDetail = "secret region detail\nmust not escape";
        ConfigurationException exception = assertThrows(
                ConfigurationException.class,
                () -> providerBuilder()
                        .serverlessCacheName(CACHE)
                        .region(null)
                        .awsRegionEnvironmentProvider(() -> null)
                        .regionProvider(() -> {
                            throw SdkClientException.create(providerDetail);
                        })
                        .build());

        assertTrue(exception.getMessage().contains("AWS_REGION"));
        assertTrue(exception.getMessage().contains("AWS SDK"));
        assertFalse(renderStackTrace(exception).contains(providerDetail));
    }

    @Test
    void nullRegionResultFailsAtConstruction() {
        ConfigurationException exception = assertThrows(
                ConfigurationException.class,
                () -> providerBuilder()
                        .serverlessCacheName(CACHE)
                        .region(null)
                        .awsRegionEnvironmentProvider(() -> null)
                        .regionProvider(() -> null)
                        .build());

        assertTrue(exception.getMessage().contains("AWS_REGION"));
        assertTrue(exception.getMessage().contains("AWS SDK"));
    }

    @Test
    void validatesParametersBeforeResolvingRegion() {
        AtomicInteger regionCalls = new AtomicInteger();

        assertThrows(
                InvalidParameterException.class,
                () -> providerBuilder()
                        .serverlessCacheName("invalid_name")
                        .region(null)
                        .awsRegionEnvironmentProvider(() -> null)
                        .regionProvider(() -> {
                            regionCalls.incrementAndGet();
                            return REGION;
                        })
                        .build());

        assertEquals(0, regionCalls.get());
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
    void retainsConstructionRegionWhileCredentialsRotate() {
        AtomicInteger regionCalls = new AtomicInteger();
        AtomicInteger credentialCalls = new AtomicInteger();
        AtomicReference<Region> regionSource = new AtomicReference<>(REGION);
        AwsCredentialsProvider credentialsProvider = () ->
                credentialCalls.getAndIncrement() == 0
                        ? AwsBasicCredentials.create(
                                "AKIAIOSFODNN7EXAMPLE", "first-secret")
                        : AwsBasicCredentials.create(
                                "AKIAI44QH8DHBEXAMPLE", "second-secret");

        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .region(null)
                .awsRegionEnvironmentProvider(() -> null)
                .regionProvider(() -> {
                    regionCalls.incrementAndGet();
                    return regionSource.get();
                })
                .credentialsProvider(credentialsProvider)
                .build();

        regionSource.set(Region.EU_WEST_1);
        String first = provider.getToken();
        regionSource.set(Region.AP_SOUTHEAST_1);
        String second = provider.getToken();

        assertEquals(1, regionCalls.get());
        assertEquals(2, credentialCalls.get());
        assertTrue(first.contains("%2Fus-east-1%2F"));
        assertTrue(second.contains("%2Fus-east-1%2F"));
        assertFalse(first.contains("%2Feu-west-1%2F"));
        assertFalse(second.contains("%2Fap-southeast-1%2F"));
        assertNotEquals(first, second);
    }

    @Test
    void missingCredentialsHasActionableError() {
        String providerDetail = "secret credential detail\nmust not escape";
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .credentialsProvider(() -> {
                    throw new IllegalStateException(providerDetail);
                })
                .build();

        ConfigurationException exception =
                assertThrows(ConfigurationException.class, provider::getToken);
        assertEquals(
                "No AWS credentials found. Configure credentials via the environment, "
                        + "shared config/credentials files, or an instance/container role.",
                exception.getMessage());
        assertFalse(renderStackTrace(exception).contains(providerDetail));
    }

    @Test
    void unusableCredentialsHaveActionableError() {
        AwsCredentials unusableCredentials = new AwsCredentials() {
            @Override
            public String accessKeyId() {
                return "";
            }

            @Override
            public String secretAccessKey() {
                return "not-a-usable-key";
            }
        };
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .credentialsProvider(() -> unusableCredentials)
                .build();

        assertThrows(ConfigurationException.class, provider::getToken);
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
    void invalidReplicationGroupIdNamesThatParameter() {
        InvalidParameterException exception = assertThrows(
                InvalidParameterException.class,
                () -> providerBuilder().replicationGroupId("bad_name!").build());

        assertTrue(exception.getMessage().contains("replication_group_id"));
    }

    @Test
    void emptyCacheNameIsRejectedAsMissingTarget() {
        assertThrows(
                InvalidParameterException.class,
                () -> providerBuilder().serverlessCacheName("").build());
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
    void signsServiceManagedDefaultUser() {
        ElastiCacheIamAuthTokenProvider provider = providerBuilder()
                .serverlessCacheName(CACHE)
                .userId("DEFAULT.IAM-USER")
                .build();

        assertEquals("default.iam-user", provider.getUserId());
        assertTrue(provider.getToken().contains("User=default.iam-user"));
    }

    @Test
    void configurationExceptionIsNotAParameterException() {
        assertFalse(InvalidParameterException.class.isAssignableFrom(ConfigurationException.class));
    }

    @Test
    void serverlessTokenMatchesKnownAnswer() {
        String token = knownAnswerProviderBuilder()
                .serverlessCacheName(CACHE)
                .build()
                .getToken();

        assertKnownAnswerToken(
                token,
                true,
                "28f349fd92f74e0192de149a74743ae7d087bb136523c342bdd67632a8360023");
    }

    @Test
    void replicationGroupTokenMatchesKnownAnswer() {
        String token = knownAnswerProviderBuilder()
                .replicationGroupId(CACHE)
                .build()
                .getToken();

        assertKnownAnswerToken(
                token,
                false,
                "cd78a7de74c1bb1c8c685cac428f783b5433ce5fbf569af867570311d6a85797");
    }

    private static ElastiCacheIamAuthTokenProvider.Builder providerBuilder() {
        return ElastiCacheIamAuthTokenProvider.builder()
                .userId(USER)
                .region(REGION)
                .credentialsProvider(StaticCredentialsProvider.create(CREDENTIALS));
    }

    private static ElastiCacheIamAuthTokenProvider.Builder knownAnswerProviderBuilder() {
        return ElastiCacheIamAuthTokenProvider.builder()
                .userId(USER)
                .region(REGION)
                .credentialsProvider(StaticCredentialsProvider.create(KNOWN_ANSWER_CREDENTIALS))
                .signingClock(KNOWN_ANSWER_CLOCK);
    }

    private static void assertValidToken(String token) {
        assertTrue(token.startsWith(CACHE + "/"));
        assertTrue(token.contains("Action=connect"));
        assertTrue(token.contains("User=" + USER));
        assertTrue(token.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
        assertTrue(token.contains("X-Amz-Credential"));
        assertTrue(token.contains("X-Amz-Security-Token=token"));
        assertTrue(token.contains("X-Amz-Signature"));
        assertTrue(token.contains("X-Amz-Expires=900"));
        assertFalse(token.startsWith("https://"));
    }

    private static String renderStackTrace(Throwable throwable) {
        StringWriter output = new StringWriter();
        throwable.printStackTrace(new PrintWriter(output));
        return output.toString();
    }

    private static void assertKnownAnswerToken(
            String token, boolean serverless, String expectedSignature) {
        assertTrue(token.startsWith(CACHE + "/?"));
        Map<String, String> parameters = rawQueryParameters(token);
        assertEquals("connect", parameters.get("Action"));
        assertEquals(USER, parameters.get("User"));
        assertEquals("AWS4-HMAC-SHA256", parameters.get("X-Amz-Algorithm"));
        assertEquals(
                "AKIAIOSFODNN7EXAMPLE%2F20250101%2Fus-east-1"
                        + "%2Felasticache%2Faws4_request",
                parameters.get("X-Amz-Credential"));
        assertEquals("20250101T000000Z", parameters.get("X-Amz-Date"));
        assertEquals("900", parameters.get("X-Amz-Expires"));
        assertEquals("host", parameters.get("X-Amz-SignedHeaders"));
        assertEquals(expectedSignature, parameters.get("X-Amz-Signature"));
        if (serverless) {
            assertEquals("ServerlessCache", parameters.get("ResourceType"));
            assertEquals(9, parameters.size());
        } else {
            assertFalse(parameters.containsKey("ResourceType"));
            assertEquals(8, parameters.size());
        }
    }

    private static Map<String, String> rawQueryParameters(String token) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String query = token.substring(token.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            parameters.put(parts[0], parts.length == 2 ? parts[1] : "");
        }
        return parameters;
    }
}

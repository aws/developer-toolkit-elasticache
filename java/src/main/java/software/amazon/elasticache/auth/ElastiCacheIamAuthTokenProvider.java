/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.auth;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

/**
 * Generates Amazon ElastiCache IAM authentication tokens.
 *
 * <p>The provider is client-agnostic. Call {@link #getToken()} whenever a Redis or
 * Valkey client opens a connection, and use {@link #getUserId()} as the ACL username.
 * A new token is signed on every call so rotating AWS credentials are picked up.
 *
 * <p>Exactly one of {@link Builder#serverlessCacheName(String)} and
 * {@link Builder#replicationGroupId(String)} is required.
 */
public final class ElastiCacheIamAuthTokenProvider {
    private static final String URL_SCHEME_PREFIX = "https://";
    private static final String SERVICE_NAME = "elasticache";
    private static final String AWS_REGION_ENV_VAR = "AWS_REGION";
    private static final Duration TOKEN_TTL = Duration.ofSeconds(900);
    private static final Pattern CACHE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*$");
    private static final Pattern USER_ID_PATTERN =
            Pattern.compile("^(?:default\\.)?[a-zA-Z][a-zA-Z0-9\\-]*$");
    private static final String NO_REGION_MESSAGE =
            "No AWS region found. Pass region explicitly, or configure one via "
                    + "AWS_REGION or the standard AWS SDK region provider chain.";
    private static final String NO_CREDENTIALS_MESSAGE =
            "No AWS credentials found. Configure credentials via the environment, "
                    + "shared config/credentials files, or an instance/container role.";

    private final String cacheName;
    private final String userId;
    private final Region region;
    private final boolean serverless;
    private final AwsCredentialsProvider credentialsProvider;
    private final AwsV4HttpSigner signer;
    private final Clock signingClock;

    private ElastiCacheIamAuthTokenProvider(Builder builder) {
        Target target = resolveTarget(builder.serverlessCacheName, builder.replicationGroupId);
        this.cacheName = target.cacheName;
        this.serverless = target.serverless;
        this.userId = validateAndNormalize("user_id", builder.userId, USER_ID_PATTERN);
        this.region = resolveRegion(
                builder.region, builder.awsRegionEnvironmentProvider, builder.regionProvider);
        this.credentialsProvider = builder.credentialsProvider == null
                ? DefaultCredentialsProvider.builder().build()
                : builder.credentialsProvider;
        this.signer = AwsV4HttpSigner.create();
        this.signingClock = builder.signingClock == null ? Clock.systemUTC() : builder.signingClock;
    }

    /**
     * Creates a builder for an IAM authentication token provider.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the normalized ElastiCache user ID to send as the ACL username.
     *
     * @return lowercase ElastiCache user ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Signs and returns a fresh IAM authentication token.
     *
     * @return presigned URL with the URI scheme removed
     * @throws ConfigurationException if credentials cannot be resolved
     */
    public String getToken() {
        AwsCredentials credentials;
        try {
            credentials = credentialsProvider.resolveCredentials();
        } catch (RuntimeException exception) {
            throw new ConfigurationException(NO_CREDENTIALS_MESSAGE);
        }
        if (credentials == null
                || !hasText(credentials.accessKeyId())
                || !hasText(credentials.secretAccessKey())) {
            throw new ConfigurationException(NO_CREDENTIALS_MESSAGE);
        }

        SdkHttpFullRequest.Builder request = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .uri(URI.create(URL_SCHEME_PREFIX + cacheName + "/"))
                .appendRawQueryParameter("Action", "connect")
                .appendRawQueryParameter("User", userId);
        if (serverless) {
            request.appendRawQueryParameter("ResourceType", "ServerlessCache");
        }

        SignedRequest signedRequest = signer.sign(signRequest -> signRequest
                .identity(credentials)
                .request(request.build())
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, SERVICE_NAME)
                .putProperty(AwsV4HttpSigner.REGION_NAME, region.id())
                .putProperty(
                        AwsV4HttpSigner.AUTH_LOCATION,
                        AwsV4HttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, TOKEN_TTL)
                .putProperty(HttpSigner.SIGNING_CLOCK, signingClock)
                .build());

        URI signedUri = ((SdkHttpFullRequest) signedRequest.request()).getUri();
        String token = signedUri.toString();
        if (!token.startsWith(URL_SCHEME_PREFIX)) {
            throw new IllegalStateException(
                    "ElastiCache IAM signer returned an unexpected URI scheme");
        }
        return token.substring(URL_SCHEME_PREFIX.length());
    }

    private static Target resolveTarget(String serverlessCacheName, String replicationGroupId) {
        boolean hasServerless = hasText(serverlessCacheName);
        boolean hasReplicationGroup = hasText(replicationGroupId);
        if (hasServerless == hasReplicationGroup) {
            throw new InvalidParameterException(
                    "Invalid parameter combination for 'serverless_cache_name' and "
                            + "'replication_group_id': exactly one must be provided.");
        }
        if (hasServerless) {
            return new Target(
                    validateAndNormalize(
                            "serverless_cache_name", serverlessCacheName, CACHE_NAME_PATTERN),
                    true);
        }
        return new Target(
                validateAndNormalize(
                        "replication_group_id", replicationGroupId, CACHE_NAME_PATTERN),
                false);
    }

    private static Region resolveRegion(
            Region explicitRegion,
            Supplier<String> awsRegionEnvironmentProvider,
            AwsRegionProvider regionProvider) {
        if (explicitRegion != null) {
            return explicitRegion;
        }

        Supplier<String> environmentProvider = awsRegionEnvironmentProvider == null
                ? () -> System.getenv(AWS_REGION_ENV_VAR)
                : awsRegionEnvironmentProvider;
        String environmentRegion = environmentProvider.get();
        if (hasText(environmentRegion)) {
            return Region.of(environmentRegion);
        }

        AwsRegionProvider provider = regionProvider == null
                ? DefaultAwsRegionProviderChain.builder().build()
                : regionProvider;
        try {
            Region resolved = provider.getRegion();
            if (resolved != null && hasText(resolved.id())) {
                return resolved;
            }
        } catch (SdkClientException exception) {
            throw new ConfigurationException(NO_REGION_MESSAGE);
        }
        throw new ConfigurationException(NO_REGION_MESSAGE);
    }

    private static String validateAndNormalize(
            String parameter, String value, Pattern pattern) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (!pattern.matcher(normalized).matches()) {
            throw new InvalidParameterException(
                    "Invalid value for parameter '" + parameter + "': must match "
                            + pattern.pattern());
        }
        return normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static final class Target {
        private final String cacheName;
        private final boolean serverless;

        private Target(String cacheName, boolean serverless) {
            this.cacheName = cacheName;
            this.serverless = serverless;
        }
    }

    /**
     * Builder for {@link ElastiCacheIamAuthTokenProvider}.
     */
    public static final class Builder {
        private String userId;
        private Region region;
        private String serverlessCacheName;
        private String replicationGroupId;
        private AwsCredentialsProvider credentialsProvider;
        private Supplier<String> awsRegionEnvironmentProvider;
        private AwsRegionProvider regionProvider;
        private Clock signingClock;

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
         * Sets the signing region. When omitted, {@code AWS_REGION} is checked before the
         * standard AWS SDK region provider chain. The AWS SDK for Java does not read
         * {@code AWS_DEFAULT_REGION}.
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
         * Sets a credentials provider. The standard AWS credentials provider chain is the default.
         *
         * @param credentialsProvider AWS credentials provider
         * @return this builder
         */
        public Builder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
            this.credentialsProvider = Objects.requireNonNull(
                    credentialsProvider, "credentialsProvider");
            return this;
        }

        Builder regionProvider(AwsRegionProvider regionProvider) {
            this.regionProvider = Objects.requireNonNull(regionProvider, "regionProvider");
            return this;
        }

        Builder awsRegionEnvironmentProvider(Supplier<String> awsRegionEnvironmentProvider) {
            this.awsRegionEnvironmentProvider = Objects.requireNonNull(
                    awsRegionEnvironmentProvider, "awsRegionEnvironmentProvider");
            return this;
        }

        Builder signingClock(Clock signingClock) {
            this.signingClock = Objects.requireNonNull(signingClock, "signingClock");
            return this;
        }

        /**
         * Validates the configuration and creates the provider.
         *
         * @return configured IAM authentication token provider
         */
        public ElastiCacheIamAuthTokenProvider build() {
            return new ElastiCacheIamAuthTokenProvider(this);
        }
    }
}

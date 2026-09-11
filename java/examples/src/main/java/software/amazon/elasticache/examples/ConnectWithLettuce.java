/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.examples;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.regions.Region;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenProvider;

/**
 * Connects to an ElastiCache serverless cache with Lettuce and IAM authentication.
 */
public final class ConnectWithLettuce {
    private static final int DEFAULT_PORT = 6379;

    private ConnectWithLettuce() {}

    /**
     * Runs the Lettuce connection example.
     *
     * @param args cache name, IAM user ID, endpoint, region, and optional port
     */
    public static void main(String[] args) {
        if (args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException(
                    "Usage: ConnectWithLettuce "
                            + "<cache-name> <user-id> <endpoint> <region> [port]");
        }

        String cacheName = args[0];
        String userId = args[1];
        String endpoint = args[2];
        Region region = Region.of(args[3]);
        int port = args.length == 5 ? Integer.parseInt(args[4]) : DEFAULT_PORT;

        ElastiCacheIamAuthTokenProvider auth = ElastiCacheIamAuthTokenProvider.builder()
                .serverlessCacheName(cacheName)
                .userId(userId)
                .region(region)
                .build();

        RedisCredentialsProvider credentialsProvider = new RedisCredentialsProvider() {
            @Override
            public Mono<RedisCredentials> resolveCredentials() {
                return Mono.fromSupplier(
                        () -> RedisCredentials.just(auth.getUserId(), auth.getToken()));
            }
        };
        RedisURI redisUri = RedisURI.Builder.redis(endpoint, port)
                .withSsl(true)
                .withAuthentication(credentialsProvider)
                .build();

        RedisClient client = RedisClient.create(redisUri);
        StatefulRedisConnection<String, String> connection = null;
        try {
            connection = client.connect();
            System.out.println("IAM authentication succeeded; PING returned "
                    + connection.sync().ping() + ".");
        } finally {
            if (connection != null) {
                connection.close();
            }
            client.shutdown();
        }
    }
}

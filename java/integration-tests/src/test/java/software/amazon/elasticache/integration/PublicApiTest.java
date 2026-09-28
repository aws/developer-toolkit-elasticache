/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.elasticache.auth.ConfigurationException;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenManager;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenProvider;
import software.amazon.elasticache.auth.InvalidParameterException;
import software.amazon.elasticache.auth.TokenRefreshException;
import software.amazon.elasticache.auth.ToolkitInputException;

class PublicApiTest {
    @Test
    void installedArtifactExposesPublicApi() {
        assertNotNull(ElastiCacheIamAuthTokenProvider.builder());
        assertNotNull(ElastiCacheIamAuthTokenManager.builder());
        try (ElastiCacheIamAuthTokenManager manager =
                ElastiCacheIamAuthTokenManager.builder()
                        .serverlessCacheName("test-cache")
                        .userId("test-user")
                        .region(Region.US_EAST_1)
                        .credentialsProvider(() ->
                                AwsBasicCredentials.create("test-access-key", "test-secret-key"))
                        .build()) {
            String token = manager.refreshToken();
            assertNotNull(token);
        }
        assertTrue(ToolkitInputException.class.isAssignableFrom(ConfigurationException.class));
        assertTrue(ToolkitInputException.class.isAssignableFrom(InvalidParameterException.class));
        assertFalse(ToolkitInputException.class.isAssignableFrom(TokenRefreshException.class));
        assertFalse(
                InvalidParameterException.class.isAssignableFrom(ConfigurationException.class));
    }
}

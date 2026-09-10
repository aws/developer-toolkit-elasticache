/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.elasticache.auth.ConfigurationException;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenProvider;
import software.amazon.elasticache.auth.InvalidParameterException;
import software.amazon.elasticache.auth.ToolkitInputException;

class PublicApiTest {
    @Test
    void installedArtifactExposesPublicApi() {
        assertNotNull(ElastiCacheIamAuthTokenProvider.builder());
        assertTrue(ToolkitInputException.class.isAssignableFrom(ConfigurationException.class));
        assertTrue(ToolkitInputException.class.isAssignableFrom(InvalidParameterException.class));
        assertFalse(
                InvalidParameterException.class.isAssignableFrom(ConfigurationException.class));
    }
}

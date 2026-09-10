/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.auth;

/**
 * Indicates that the AWS environment is missing configuration required to sign a token.
 */
public final class ConfigurationException extends ToolkitInputException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a configuration exception.
     *
     * @param message actionable error message
     */
    public ConfigurationException(String message) {
        super(message);
    }
}

/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.auth;

/**
 * Indicates an invalid parameter value or parameter combination.
 */
public final class InvalidParameterException extends ToolkitInputException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an invalid parameter exception.
     *
     * @param message actionable error message
     */
    public InvalidParameterException(String message) {
        super(message);
    }
}

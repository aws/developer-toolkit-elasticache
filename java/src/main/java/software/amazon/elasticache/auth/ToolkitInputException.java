/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.auth;

/**
 * Base class for IAM token generation failures that callers can correct.
 */
public class ToolkitInputException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a correctable toolkit exception.
     *
     * @param message actionable error message
     */
    public ToolkitInputException(String message) {
        super(message);
    }

    /**
     * Creates a correctable toolkit exception with its cause.
     *
     * @param message actionable error message
     * @param cause underlying failure
     */
    public ToolkitInputException(String message, Throwable cause) {
        super(message, cause);
    }
}

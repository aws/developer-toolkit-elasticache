/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.auth;

/**
 * Indicates that a cached token could not be refreshed before it expired, or
 * that its token manager was closed.
 *
 * <p>This is an operational failure rather than an invalid-input error, so it
 * does not extend {@link ToolkitInputException}.
 */
public final class TokenRefreshException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a token refresh exception.
     *
     * @param message actionable error message
     */
    public TokenRefreshException(String message) {
        super(message);
    }

    /**
     * Creates a token refresh exception with its underlying refresh failure.
     *
     * @param message actionable error message
     * @param cause last refresh failure
     */
    public TokenRefreshException(String message, Throwable cause) {
        super(message, cause);
    }
}

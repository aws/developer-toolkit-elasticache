// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * Base class for failures that can be corrected by changing the invocation or
 * the AWS configuration.
 */
export class ToolkitInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ToolkitInputError";
  }
}

/**
 * A parameter value or combination of values is invalid.
 */
export class InvalidParameterError extends ToolkitInputError {
  constructor(message: string) {
    super(message);
    this.name = "InvalidParameterError";
  }
}

/**
 * Required AWS configuration, such as a region or credentials, is missing.
 */
export class ConfigurationError extends ToolkitInputError {
  constructor(message: string) {
    super(message);
    this.name = "ConfigurationError";
  }
}

// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

export {
  ConfigurationError,
  InvalidParameterError,
  ToolkitInputError,
} from "./errors.js";
export type {
  TokenGeneratorDependencies,
  TokenGeneratorOptions,
} from "./token-generator.js";
export {
  ElastiCacheIAMAuthTokenProvider,
  generateIamAuthToken,
} from "./token-generator.js";

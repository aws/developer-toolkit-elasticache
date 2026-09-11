# Changelog

All notable changes to this project's language packages are documented here. The
format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and each
package uses [Semantic Versioning](https://semver.org/).

## Java

### [Unreleased]

#### Added

- Initial Java IAM authentication token generator for serverless caches and
  node-based replication groups.
- Input validation, lowercase normalization, AWS region and credential resolution,
  typed user-correctable errors, and deterministic SigV4 signing tests.
- Credential providers that fail or return unusable credentials are normalized to
  an actionable `ConfigurationException`.
- Maven build, Java version CI matrix, documentation, and dependency updates.

#### Notes

- Region resolution follows the Java contract: explicit region, then `AWS_REGION`,
  then the standard AWS SDK region provider chain. Unlike botocore, the AWS SDK for
  Java does not read `AWS_DEFAULT_REGION`.
- Java examples demonstrate Lettuce `RedisCredentialsProvider` integration and the
  raw TLS/Redis protocol exchange for custom clients and proxies. The wire-level
  example verifies the endpoint hostname during the TLS handshake.

## Python

### [1.0.0] - Unreleased

Initial release.

#### Added

- `generate_iam_auth_token()` — signs an IAM authentication token for an Amazon
  ElastiCache serverless cache or node-based replication group using the default AWS
  credential chain.
- `ElastiCacheIAMAuthTokenProvider` — signs a fresh token on each `get_token()` call,
  for clients that re-authenticate on reconnect.
- `developer-toolkit-elasticache generate_iam_auth_token` command-line interface.
- `InvalidParameterError` and `ConfigurationError` (both `ToolkitInputError`) for
  invalid arguments and unresolvable region or credentials. Error messages never echo
  caller-provided values.
- Examples for redis-py and valkey-py under `examples/`.

[1.0.0]: https://github.com/aws/developer-toolkit-elasticache/releases/tag/python-v1.0.0

# Changelog

All notable changes to the Java package will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this package uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Initial Java IAM authentication token generator for serverless caches and
  node-based replication groups.
- Input validation, lowercase normalization, AWS region and credential resolution,
  typed user-correctable errors, and deterministic SigV4 signing tests.
- Credential providers that fail or return unusable credentials are normalized to
  an actionable `ConfigurationException`.
- Maven build, Java version CI matrix, documentation, and dependency updates.

### Notes

- Region resolution follows the Java contract: explicit region, then `AWS_REGION`,
  then the standard AWS SDK region provider chain. Unlike botocore, the AWS SDK for
  Java does not read `AWS_DEFAULT_REGION`.
- Java examples demonstrate Lettuce `RedisCredentialsProvider` integration and the
  raw TLS/Redis protocol exchange for custom clients and proxies. The wire-level
  example verifies the endpoint hostname during the TLS handshake.

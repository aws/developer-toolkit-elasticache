# Changelog

All notable changes to the Java package will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Initial Java IAM authentication token generator for serverless caches and
  node-based replication groups.
- Input validation, lowercase normalization, AWS region and credential resolution,
  typed user-correctable errors, and deterministic SigV4 signing tests.
- Maven build, Java version CI matrix, documentation, and dependency updates.

### Notes

- Region resolution follows the Java contract: explicit region, then `AWS_REGION`,
  then the standard AWS SDK region provider chain. Unlike botocore, the AWS SDK for
  Java does not read `AWS_DEFAULT_REGION`.
- A standalone Java example demonstrates TLS connection, IAM authentication, and
  `PING` against an ElastiCache serverless cache without adding a client-library
  dependency to the toolkit.

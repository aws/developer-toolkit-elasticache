# Changelog

All notable changes to the Node.js package are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the package uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `generateIamAuthToken()` for signing IAM authentication tokens for Amazon ElastiCache
  serverless caches and node-based replication groups.
- `ElastiCacheIAMAuthTokenProvider` for generating fresh tokens when clients connect or
  reconnect.
- `ToolkitInputError`, `InvalidParameterError`, and `ConfigurationError`.
- Runnable token-generation and iovalkey integration examples under `examples/`.

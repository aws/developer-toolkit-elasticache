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
- `ElastiCacheIAMAuthTokenManager` for caching a token, refreshing it in the background
  before it expires, retrying transient refresh failures, and notifying an
  `onTokenChanged` callback.
- `ToolkitInputError`, `InvalidParameterError`, `ConfigurationError`, and
  `TokenRefreshError`.
- Runnable token-generation and iovalkey integration examples under `examples/`.

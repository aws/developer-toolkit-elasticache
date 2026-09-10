# Changelog

All notable changes to the Python package are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the package uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - Unreleased

Initial release.

### Added

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

# Changelog

All notable changes to the Python package are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the package uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `ElastiCacheIAMAuthTokenManager` — caches a token for 880 seconds (its 900-second
  lifetime minus a 20-second serve margin) and refreshes it on a background daemon
  thread `refresh_after` seconds after signing (default 300). Concurrent callers share
  a single signing cycle and its outcome; failed refreshes are retried with jittered
  exponential backoff while the current token is still served, with a warning logged
  per failed cycle; `on_token_changed` reports each new token; `get_credentials()`
  returns `(user_id, token)`; `refresh_token()` signs a replacement on demand;
  `close()` or a `with` block stops background work.
- `TokenRefreshError` — raised by the manager when a cached token expired before a
  refresh could replace it, or after `close()`; `__cause__` is the last refresh error.
  Not a `ToolkitInputError`.
- `DEFAULT_REFRESH_AFTER_SECONDS` constant.

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

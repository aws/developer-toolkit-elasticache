# Developer Toolkit for Amazon ElastiCache (Python)

This is a developer toolkit for working with Amazon ElastiCache, as a Python library and
CLI.
It provides a function to generate an [IAM authentication token](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/auth-iam.html) that Amazon ElastiCache requires as the
connection password for an IAM-enabled user. 

## Installation

Requires Python 3.10–3.14 and `pip`. Install from PyPI:

```bash
python3 -m pip install developer-toolkit-elasticache
```

If you don't have [`pip`](https://pip.pypa.io) installed, this
[Python installation guide](https://docs.python-guide.org/starting/installation/) can
guide you through the process.

This installs the library and the `developer-toolkit-elasticache` command. To install
from source instead, clone the repository and run `python3 -m pip install .` from the
`python/` directory.

## Usage

To generate a usable token you need:

- An Amazon ElastiCache serverless cache or replication group with the following:
  - Valkey 7.2 and above or Redis OSS 7.0 and above
  - In-transit Encryption (TLS) enabled
  - IAM-enabled user that has access to to the cache.
- AWS credentials on the default credential chain (environment variables, shared
  config files, or an instance/container role), for an identity with the
  `elasticache:Connect` permission to the cache.

### Generate a token

```python
from developer_toolkit_elasticache import generate_iam_auth_token

token = generate_iam_auth_token(
    serverless_cache_name="my-cache",  # or replication_group_id="my-group"
    user_id="iam-user",
    region="us-east-1",
)
```
Use the returned token as the password when connecting to the cache.

**Arguments**

- `serverless_cache_name` or `replication_group_id` (string) [one required]
The serverless cache or node-based replication group to sign for. 
- `user_id` (string) [required]
The IAM-enabled user to authenticate as.
- `region` (string) [optional]
The AWS region. Defaults to your AWS configuration (`AWS_REGION`,
`AWS_DEFAULT_REGION`, or the `region` in your profile, in that order).

**Credentials**

Credentials are resolved through the standard AWS credential provider chain
and are re-read on every call, so rotated credentials are picked up for every new token generation.
The chain is checked in this order:

1. Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and
   `AWS_SESSION_TOKEN`).
2. The shared credentials and config files (`~/.aws/credentials`, `~/.aws/config`),
   selected by `AWS_PROFILE`, including any assume-role or SSO configuration.
3. Container credentials (Amazon ECS / EKS).
4. EC2 instance profile credentials (IMDS).

### Reconnecting clients

Clients such as redis-py and valkey-py request credentials on every reconnection.
Pass them an `ElastiCacheIAMAuthTokenProvider` and call `get_token()` when the
connection opens, so each connection uses a fresh token:

```python
from developer_toolkit_elasticache import ElastiCacheIAMAuthTokenProvider

auth = ElastiCacheIAMAuthTokenProvider(
    serverless_cache_name="my-cache",
    user_id="iam-user",
    region="us-east-1",
)
username, password = auth.user_id, auth.get_token()
```

The [`examples/`](examples/) directory has redis-py and valkey-py integrations.

### Caching and background refresh

`ElastiCacheIAMAuthTokenProvider` is stateless: every `get_token()` call resolves
credentials and signs a new token. `ElastiCacheIAMAuthTokenManager` adds caching on
top of that:

|                        | `ElastiCacheIAMAuthTokenProvider` | `ElastiCacheIAMAuthTokenManager`                |
| ---------------------- | --------------------------------- | ----------------------------------------------- |
| Token per `get_token()`| A freshly signed token every call | The cached token while it is valid              |
| Refresh                | Caller-driven                     | Background, `refresh_after` seconds after signing |
| Refresh failures       | Raised to the caller              | Retried, with the current token still served    |
| Change notification    | None                              | `on_token_changed`                              |
| Cleanup                | None needed                       | `close()`, or use as a context manager          |

Use the manager when a client reconnects often or asks for a token more often than
the 900-second token lifetime; use the provider when each call should sign a new
token.

```python
from developer_toolkit_elasticache import ElastiCacheIAMAuthTokenManager

with ElastiCacheIAMAuthTokenManager(
    serverless_cache_name="my-cache",
    user_id="iam-user",
    region="us-east-1",
    # Optional; defaults to 300. Must be greater than 0 and less than 880.
    refresh_after=300,
    on_token_changed=lambda token: print("installed a new IAM auth token"),
) as auth:
    username, password = auth.get_credentials()
```

`get_credentials()` returns `(user_id, token)` for clients that take a no-argument
credentials callable; `get_token()` remains available when the client takes the
username and password separately. `refresh_token()` signs a replacement immediately
and returns it, for a client whose `AUTH` was rejected with a token the manager still
considers valid (for example after the signing credentials were revoked); it raises
`TokenRefreshError` if no replacement could be signed.

The region is resolved when the manager is created, but no credentials are resolved
and no token is signed until the first `get_token()` or `get_credentials()` call.
After that, `get_token()` returns the cached token immediately while it is valid. A
token is served for 880 seconds after signing — its 900-second lifetime minus a
20-second margin for clock skew and the `AUTH` round trip — and `refresh_after` must
be below that. A background thread signs a replacement `refresh_after` seconds after
each token is issued; concurrent callers with no valid token share a single signing
and receive the same outcome.

A refresh makes up to 8 attempts, the first immediate and the rest with exponential
backoff from 100 ms to a nominal cap of 5 seconds, each delay jittered by ±20%. Every
error is retried, including `ConfigurationError`: on an instance role a transient
IMDS failure surfaces as "no credentials". A `get_token()` call with no valid token
blocks for that cycle, so it can take several seconds when signing keeps failing.

While the current token is still valid a failed refresh is invisible to callers: the
cached token keeps being served, another refresh is scheduled, and a warning is
logged to the `developer_toolkit_elasticache.token_manager` logger. `get_token()`
raises `TokenRefreshError` only if the cached token expired before a refresh could
replace it, or after `close()`; its `__cause__` is the error from the last attempt. A
failure to sign the very first token is raised as the underlying error instead.

`on_token_changed` must be a synchronous callable; if it raises, the error is logged
and the new token is kept. The refresh thread is a daemon, so it never keeps the
interpreter alive on its own. Call `close()` (or leave the `with` block) when the
manager is no longer needed.

### Command line

```bash
developer-toolkit-elasticache generate_iam_auth_token \
  --serverless-cache-name my-cache \
  --user-id iam-user \
  --region us-east-1
```

Add `--region` to sign for a specific region instead of the one resolved from your
AWS configuration

For the command line, the token is written to stdout, so you can capture it
directly and pass it to a CLI client through an auth environment variable.
`REDISCLI_AUTH` works with both `redis-cli` and `valkey-cli`; `VALKEYCLI_AUTH`
works with `valkey-cli` 9.0.0 and later:

```bash
export VALKEYCLI_AUTH=$(developer-toolkit-elasticache generate_iam_auth_token \
  --serverless-cache-name my-cache --user-id iam-user --region us-east-1)

# Example: connecting via valkey-cli
valkey-cli --tls -h <my-cache-configured-endpoint> --user <iam-user>
```

## Security

The token is a bearer credential. Keep it out of logs and shell history, and
connect over TLS. When using a CLI client, you can use the `REDISCLI_AUTH` /
`VALKEYCLI_AUTH` environment variable to pass the token more safely than the
`-a` / `--pass` flags, which expose it in the process list.

## License

Apache-2.0. See [LICENSE](LICENSE).

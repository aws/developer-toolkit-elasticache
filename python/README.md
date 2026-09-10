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

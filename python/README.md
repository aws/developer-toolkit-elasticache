# Developer Toolkit for Amazon ElastiCache (Python)

Developer tools for working with Amazon ElastiCache, as a Python library and
CLI. 

Install from source:

```bash
python -m pip install -e ".[dev]"
```

This also registers the `developer-toolkit-elasticache` CLI.

## Tools

### IAM authentication tokens

IAM authentication for ElastiCache requires presenting a short-lived
SigV4-signed token (valid for 15 minutes) as the connection password. This tool
handles the signing, eliminating the boilerplate and the several non-obvious
details that otherwise surface as an opaque `WRONGPASS` at connect time:

- The token is signed against the **cache name** (or replication group id), not
  the connection endpoint DNS.
- Serverless caches sign `ResourceType=ServerlessCache`; node-based replication
  groups omit it. The resource type is part of the signature, so it cannot be
  corrected after the fact.
- Cache names are lowercased at creation time, so they must be signed in
  lowercase.

A fresh token is signed on demand each time one is requested, so there is no
background refresh thread to manage and no stale-token window. Credentials are
re-read from the default AWS credential chain on every call, so rotated
credentials are picked up automatically.

```python
from developer_toolkit_elasticache import generate_iam_auth_token

token = generate_iam_auth_token(
    serverless_cache_name="my-cache",
    user_id="iam-user",
)
```

For a node-based cluster, pass `replication_group_id` instead of
`serverless_cache_name`.

`region` is optional: it defaults to the region your AWS configuration resolves —
`AWS_REGION`, `AWS_DEFAULT_REGION`, or the `region` setting in your config profile,
in that order — and only needs to be passed to sign for a different region.

For clients that re-authenticate on reconnect (redis-py, valkey-py), use
`ElastiCacheIAMAuth` and let the client call `get_token()` when it opens a
connection. See the [`examples/`](examples/) directory.

From the CLI:

```bash
developer-toolkit-elasticache generate_iam_auth_token \
  --serverless-cache-name my-cache --user-id iam-user
```

The token is a bearer credential. Pass it via the client's password parameter or
`VALKEYCLI_AUTH` / `REDISCLI_AUTH`.

## Development

```bash
python -m pip install -e ".[dev]"   # install with test + lint dependencies
python -m pytest -q                 # run the tests
python -m ruff check .              # lint
python -m ruff format .             # format
```

The test suite injects credentials into every signing call, so it never reads the
AWS credential chain and needs no AWS account to run.

## Status

Proof of concept

## License

Apache-2.0. See [LICENSE](LICENSE).

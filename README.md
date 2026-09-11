# Developer Toolkit for ElastiCache

This repository contains libraries and tools for working with
[Amazon ElastiCache](https://aws.amazon.com/elasticache/).

The Python and Node.js libraries support
[IAM authentication](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/auth-iam.html)
for ElastiCache. They generate the signed token required to connect with an
IAM-enabled user, allowing applications to authenticate to a Valkey or Redis OSS
cache with AWS credentials.

## Packages

| Language | Package | Documentation |
|---|---|---|
| Python | [`developer-toolkit-elasticache`](https://pypi.org/project/developer-toolkit-elasticache/) on PyPI | [python/README.md](python/README.md) |
| Node.js | `@aws/developer-toolkit-elasticache` on npm | [node/README.md](node/README.md) |

## Quick start

### Python

```bash
python3 -m pip install developer-toolkit-elasticache
```

```python
from developer_toolkit_elasticache import generate_iam_auth_token

token = generate_iam_auth_token(
    serverless_cache_name="my-cache",  # or replication_group_id="my-group"
    user_id="iam-user",
    region="us-east-1",
)
```

Use the returned token as the password when connecting to the cache. See the
[Python README](python/README.md) for requirements, the credential chain, the Python
command-line tool, and client integration examples.

### Node.js

```bash
npm install @aws/developer-toolkit-elasticache
```

```typescript
import { generateIamAuthToken } from "@aws/developer-toolkit-elasticache";

const token = await generateIamAuthToken({
  serverlessCacheName: "my-cache", // or replicationGroupId: "my-group"
  userId: "iam-user",
  region: "us-east-1",
});
```

Use the returned token as the password when connecting to the cache. See the
[Node.js README](node/README.md) for requirements, the credential and region
chains, the async token provider, and client integration examples.

## Getting help

Please use these community resources for getting help. We use the GitHub issues for
tracking bugs and feature requests.

- Ask a question or [open a discussion](https://github.com/aws/developer-toolkit-elasticache/discussions).
- If you think you may have found a bug, please [open an issue](https://github.com/aws/developer-toolkit-elasticache/issues/new).


## Contributing

Contributions are welcome. Open an issue to discuss a change before sending a pull
request; all changes are reviewed before merging.

## License

This project is licensed under the Apache-2.0 License. See [LICENSE](LICENSE) and
[NOTICE](NOTICE).

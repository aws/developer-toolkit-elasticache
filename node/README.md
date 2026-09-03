# Developer Toolkit for Amazon ElastiCache (Node.js)

This package generates IAM authentication tokens for Amazon ElastiCache serverless caches
and node-based replication groups. It provides a typed TypeScript API, an async token
provider, and the `generate_iam_auth_token` command.

## Installation

The package requires Node.js 22 or newer. To install the current source package:

```bash
git clone https://github.com/aws/developer-toolkit-elasticache.git
cd developer-toolkit-elasticache/node
npm ci
npm run build
npm install .
```

For a published package, install it with npm:

```bash
npm install @aws/developer-toolkit-elasticache
```

## Usage

The cache must have TLS enabled and an IAM-enabled user with access to the cache. The AWS
identity used by the credential chain must have the `elasticache:Connect` permission.

### Generate a token

```typescript
import { generateIamAuthToken } from "@aws/developer-toolkit-elasticache";

const token = await generateIamAuthToken({
  serverlessCacheName: "my-cache",
  // Use replicationGroupId: "my-group" for a node-based replication group.
  userId: "iam-user",
  region: "us-east-1",
});
```

Provide exactly one of `serverlessCacheName` and `replicationGroupId`. The value is the
ElastiCache cache name or replication group id used as the SigV4 signing host; it is not
the connection endpoint DNS name. Cache names and user ids are normalized to lowercase
before validation and signing.

The `region` option is optional. Resolution uses the following order:

1. The explicit `region` option.
2. `AWS_REGION`.
3. `AWS_DEFAULT_REGION`.
4. The region in the standard AWS shared configuration.

Credentials use the standard Node.js AWS provider chain, including environment variables,
shared config and credentials files, SSO, assume-role, container credentials, and EC2
instance metadata. One-shot calls resolve the region and credentials for each invocation,
so refreshed configuration and credentials are used.

### Reconnecting clients

`ElastiCacheIAMAuthTokenProvider` is client-agnostic. It resolves and retains the region
once during construction, exposes the normalized `userId`, and asynchronously vends a
fresh token each time `getToken()` is called. Credential providers are still invoked for
every token, so refreshed credentials are used without changing the signing region. Since
Node's region configuration is asynchronous, missing region configuration is reported by
`getToken()` rather than the synchronous constructor:

```typescript
import { ElastiCacheIAMAuthTokenProvider } from "@aws/developer-toolkit-elasticache";

const auth = new ElastiCacheIAMAuthTokenProvider({
  serverlessCacheName: "my-cache",
  userId: "iam-user",
  region: "us-east-1",
});

const username = auth.userId;
const password = await auth.getToken();
```

Pass `username` and `password` to the client connection configuration. Fetch another token
whenever the client creates a new connection. See
[`examples/connect-valkey.mjs`](examples/connect-valkey.mjs) for a Valkey GLIDE bridge.

### Examples

The package includes runnable ESM examples for Node.js 22 and newer. After installing the
package and configuring AWS credentials, run:

```bash
node examples/generate-token.mjs
```

The Valkey GLIDE example uses an optional client dependency and does not add it to this
package's runtime dependencies. Install it in the application that runs the example, then
run:

```bash
npm install @valkey/valkey-glide
node examples/connect-valkey.mjs
```

### Command line

The npm package installs the `generate_iam_auth_token` executable:

```bash
generate_iam_auth_token \
  --serverless-cache-name my-cache \
  --user-id iam-user \
  --region us-east-1
```

The Python-compatible form with a leading command is also accepted:

```bash
generate_iam_auth_token generate_iam_auth_token \
  --replication-group-id my-group \
  --user-id iam-user
```

The token is the only successful stdout output, followed by a newline. Input and AWS
configuration errors produce one `Error: ...` line on stderr and exit with status 1.
Malformed command-line arguments exit with status 2.

## Errors

The public error hierarchy is:

- `ToolkitInputError`: base class for user-correctable failures.
- `InvalidParameterError`: invalid values or target combinations.
- `ConfigurationError`: missing AWS region or credentials.

## Security

An IAM auth token is a short-lived bearer credential. Do not log, persist, or place it in
shell history. Connect to ElastiCache over TLS. When using a command-line client, pass the
token through `REDISCLI_AUTH` or `VALKEYCLI_AUTH` instead of a password argument that may
appear in the process list:

```bash
export VALKEYCLI_AUTH=$(generate_iam_auth_token \
  --serverless-cache-name my-cache \
  --user-id iam-user \
  --region us-east-1)
valkey-cli --tls -h <configured-connection-endpoint> --user iam-user
unset VALKEYCLI_AUTH
```

## Development

```bash
npm ci
npm run lint
npm run format:check
npm run typecheck
npm test
npm run build
npm run package:check
```

## License

Apache-2.0. See [LICENSE](LICENSE).

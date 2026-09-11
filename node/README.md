# Developer Toolkit for Amazon ElastiCache (Node.js)

This package generates IAM authentication tokens for Amazon ElastiCache serverless caches
and node-based replication groups. It provides a typed TypeScript API and an async token
provider.

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

The `region` option is optional when a region is available through the standard AWS
configuration chain. If no region can be resolved, the operation throws
`ConfigurationError`.

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
every token, so refreshed credentials are used without changing the signing region.

Node's region configuration is asynchronous, so use the `create()` factory to surface a
missing region up front. The synchronous constructor is also supported, but it retains the
configuration error and reports it from the first `getToken()` call instead.

```typescript
import { ElastiCacheIAMAuthTokenProvider } from "@aws/developer-toolkit-elasticache";

const auth = await ElastiCacheIAMAuthTokenProvider.create({
  serverlessCacheName: "my-cache",
  userId: "iam-user",
  region: "us-east-1",
});

const username = auth.userId;
const password = await auth.getToken();
```

Pass `username` and `password` to the client connection configuration. Fetch another token
whenever the client creates a new connection. See
[`examples/connect-iovalkey.mjs`](examples/connect-iovalkey.mjs) for an iovalkey
integration.

### Examples

The source repository includes runnable ESM examples for Node.js 22 and newer. After
installing the package and configuring AWS credentials, run:

```bash
node examples/generate-token.mjs
```

The iovalkey example demonstrates integration with a client that does not provide built-in
ElastiCache IAM authentication. It is an optional dependency and is not added to this
package's runtime dependencies. Install it in the application that runs the example, then
run:

```bash
npm install iovalkey
node examples/connect-iovalkey.mjs
```

## Errors

The public error hierarchy is:

- `ToolkitInputError`: base class for user-correctable failures.
- `InvalidParameterError`: invalid values or target combinations.
- `ConfigurationError`: missing AWS region or credentials.

## Security

An IAM auth token is a short-lived bearer credential. Do not log, persist, or place it in
shell history. Connect to ElastiCache over TLS. When using a command-line client, pass the
token through `REDISCLI_AUTH` or `VALKEYCLI_AUTH` instead of a password argument that may
appear in the process list. Generate the token programmatically with
`generateIamAuthToken()` and avoid logging or persisting it.

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

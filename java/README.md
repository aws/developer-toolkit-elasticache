# Developer Toolkit for Amazon ElastiCache (Java)

This Java library generates the IAM authentication token that Amazon ElastiCache
requires as the connection password for an IAM-enabled user. It supports both
serverless caches and node-based replication groups.

## Installation

Requires Java 8 or later and Maven. To build and install the current source into
your local Maven repository:

```bash
cd java
mvn install
```

Add the dependency to another Maven project:

```xml
<dependency>
    <groupId>software.amazon.elasticache</groupId>
    <artifactId>developer-toolkit-elasticache</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## What you need

- An Amazon ElastiCache serverless cache or replication group with:
  - Valkey 7.2 or later, or Redis OSS 7.0 or later.
  - In-transit encryption (TLS) enabled.
  - An IAM-enabled ElastiCache user with access to the cache.
- AWS credentials for an identity with `elasticache:Connect` permission for both
  the cache and user resources.

Credentials use the AWS SDK default provider chain, including environment
variables, shared credentials/config files, container credentials, and EC2 instance
profile credentials. To use a specific provider, pass it with
`credentialsProvider(...)`.

## Generate a token

```java
import software.amazon.awssdk.regions.Region;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenProvider;

ElastiCacheIamAuthTokenProvider auth = ElastiCacheIamAuthTokenProvider.builder()
        .serverlessCacheName("my-cache") // or replicationGroupId("my-group")
        .userId("iam-user")
        .region(Region.US_EAST_1)
        .build();

String username = auth.getUserId();
String password = auth.getToken();
```

Use `username` and `password` when opening the Redis or Valkey connection. Tokens
are valid for 15 minutes, and the provider reads fresh AWS credentials on every
`getToken()` call.

The region is optional. When omitted, the toolkit checks `AWS_REGION` first,
then uses the standard AWS SDK region provider chain.

The AWS SDK for Java does not read `AWS_DEFAULT_REGION`. If your environment
uses that variable for Python or AWS CLI tooling, also set `AWS_REGION` or pass
the region explicitly when using this Java package.

Missing credentials are reported when a token is requested. Expired or otherwise
invalid credentials cannot be detected during local signing: token generation can
succeed, but ElastiCache will reject the token when the client connects.

## Reconnecting clients

Clients should request fresh credentials whenever they connect or reconnect. The
Lettuce example implements `RedisCredentialsProvider` so each credentials request
calls `getToken()`:

```java
RedisCredentialsProvider credentialsProvider = new RedisCredentialsProvider() {
    @Override
    public Mono<RedisCredentials> resolveCredentials() {
        return Mono.fromSupplier(
                () -> RedisCredentials.just(auth.getUserId(), auth.getToken()));
    }
};
```

Run the complete
[`ConnectWithLettuce`](examples/src/main/java/software/amazon/elasticache/examples/ConnectWithLettuce.java)
example after installing the toolkit:

```bash
mvn --file examples/pom.xml compile exec:java \
    -Dexec.mainClass=software.amazon.elasticache.examples.ConnectWithLettuce \
    -Dexec.args="<cache-name> <iam-user-id> <endpoint> <region>"
```

For custom clients and proxies,
[`ConnectServerlessCache`](examples/src/main/java/software/amazon/elasticache/examples/ConnectServerlessCache.java)
shows the TLS and Redis serialization protocol exchange without a client library:

```bash
mvn --file examples/pom.xml compile exec:java \
    -Dexec.mainClass=software.amazon.elasticache.examples.ConnectServerlessCache \
    -Dexec.args="<cache-name> <iam-user-id> <endpoint> <region>"
```

The wire-level example enables certificate hostname verification before sending
the IAM token.

## Security

The generated token is a bearer credential. Keep it out of logs and connect to the
cache over TLS.

## License

Apache-2.0. See [LICENSE](LICENSE).

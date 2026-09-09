# Developer Toolkit for Amazon ElastiCache (Java)

This Java library generates the IAM authentication token that Amazon ElastiCache
requires as the connection password for an IAM-enabled user. It supports both
serverless caches and node-based replication groups.

## Requirements

- Java 8 or later
- AWS credentials available through the standard AWS SDK credential provider chain

## Build

```bash
cd java
mvn verify
```

To use the current source build from another local Maven project:

```bash
mvn install
```

```xml
<dependency>
    <groupId>software.amazon.elasticache</groupId>
    <artifactId>developer-toolkit-elasticache</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

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

Use `username` and `password` when opening the Redis or Valkey connection. Call
`getToken()` again for each reconnect; tokens are valid for 15 minutes and the
provider reads fresh AWS credentials on every call.

The region is optional. When omitted, the toolkit checks `AWS_REGION` first,
then uses the standard AWS SDK region provider chain.

The AWS SDK for Java does not read `AWS_DEFAULT_REGION`. If your environment
uses that variable for Python or AWS CLI tooling, also set `AWS_REGION` or pass
the region explicitly when using this Java package.

Credentials use the AWS SDK default provider chain, including environment
variables, shared credentials/config files, container credentials, and EC2 instance
profile credentials. To use a specific provider, pass it with
`credentialsProvider(...)`.

Missing credentials are reported when a token is requested. Expired or otherwise
invalid credentials cannot be detected during local signing: token generation can
succeed, but ElastiCache will reject the token when the client connects.

## Connect to a serverless cache

The standalone example in [`examples`](examples) opens a TLS connection, authenticates
with a generated IAM token, and sends `PING`. It uses the Redis serialization protocol
directly so the toolkit does not require a particular Redis or Valkey client.

Install the toolkit into your local Maven repository, then run the example:

```bash
mvn install
mvn --file examples/pom.xml compile exec:java \
    -Dexec.args="<cache-name> <iam-user-id> <endpoint> <region>"
```

Applications should normally use their preferred client library and provide
`auth.getUserId()` and a fresh `auth.getToken()` whenever the client authenticates
or reconnects.

## Prerequisites

The target cache must have TLS enabled and an IAM-enabled ElastiCache user. The
calling AWS identity needs `elasticache:Connect` permission for both the cache and
user resources.

## Security

The generated token is a bearer credential. Keep it out of logs and connect to the
cache over TLS.

## License

Apache-2.0. See [LICENSE](LICENSE).

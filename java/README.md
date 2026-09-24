# Developer Toolkit for Amazon ElastiCache (Java)

This Java library generates the IAM authentication token that Amazon ElastiCache
requires as the connection password for an IAM-enabled user. It supports both
serverless caches and node-based replication groups, including optional token
caching and background refresh.

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

## Caching and background refresh

`ElastiCacheIamAuthTokenProvider` is stateless and signs a new token on every
`getToken()` call. `ElastiCacheIamAuthTokenManager` caches a token and refreshes
it in the background:

| | `ElastiCacheIamAuthTokenProvider` | `ElastiCacheIamAuthTokenManager` |
|---|---|---|
| Token per `getToken()` | Freshly signed every call | Cached while valid |
| Refresh | Caller-driven | Background after `refreshAfter` |
| Refresh failures | Surfaced to the caller | Retried while the current token is valid |
| Change notification | None | `onTokenChanged` |
| Cleanup | None needed | `close()` |

```java
import java.time.Duration;
import software.amazon.awssdk.regions.Region;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenManager;

try (ElastiCacheIamAuthTokenManager auth =
        ElastiCacheIamAuthTokenManager.builder()
                .serverlessCacheName("my-cache")
                .userId("iam-user")
                .region(Region.US_EAST_1)
                .refreshAfter(Duration.ofMinutes(5))
                .onTokenChanged(token ->
                        System.out.println("Installed a new IAM auth token"))
                .build()) {
    ElastiCacheIamAuthTokenManager.Credentials credentials = auth.getCredentials();
    String username = credentials.getUserId();
    String password = credentials.getToken();
}
```

The first token is generated lazily. Concurrent callers share one initial token
generation, and subsequent calls return the cached token while it is valid.
Refresh starts after 5 minutes by default; `refreshAfter(...)` accepts values from
1 millisecond up to, but not including, 14 minutes 40 seconds. The manager reserves
the final 20 seconds of the service's 15-minute token lifetime for clock skew and
in-transit reconnects. Token-change callbacks run on the manager's refresh thread
after the new token is available to callers and should return quickly so they do
not delay that manager's next refresh.

A refresh makes up to 8 attempts with jittered exponential backoff capped at 5
seconds. If refresh fails while the cached token is still valid, callers continue
to receive that token and another refresh is scheduled. If the token expires
before it can be replaced, `getToken()` throws `TokenRefreshException` with the
last refresh failure as its cause.

Call `refreshToken()` after a client rejects the cached token, instead of waiting
for the scheduled refresh. It invalidates that token immediately, resolves AWS
credentials again, signs and installs a replacement, and returns it. Concurrent
`refreshToken()`, `getToken()`, and `getCredentials()` calls share the same
replacement operation, including a background refresh already in progress. If the
replacement cannot be signed, the rejected token remains invalidated and the
failure is reported to the caller.

The refresh thread is a daemon and does not keep the JVM alive. Close the manager
to cancel background work; token requests after close throw
`TokenRefreshException`.

Use the manager when a client reconnects often or requests credentials more
frequently than the token lifetime. Use the provider when every request should
sign a new token.

## Reconnecting clients

Clients should request fresh credentials whenever they connect or reconnect. The
Lettuce example implements `RedisCredentialsProvider` so each credentials request
uses the manager's current token:

```java
RedisCredentialsProvider credentialsProvider = new RedisCredentialsProvider() {
    @Override
    public Mono<RedisCredentials> resolveCredentials() {
        return Mono.fromSupplier(() -> {
            ElastiCacheIamAuthTokenManager.Credentials credentials =
                    auth.getCredentials();
            return RedisCredentials.just(
                    credentials.getUserId(), credentials.getToken());
        });
    }
};
```

If the client reports an AUTH rejection, discard that connection before forcing a
refresh and beginning the next bounded connection attempt:

```java
try {
    return openConnection(credentialsProvider);
} catch (RedisAuthenticationException authFailure) {
    // Never return the rejected connection to the pool.
    closeRejectedConnection();

    // Resolve AWS credentials again and install a replacement token. The next
    // openConnection call obtains it through credentialsProvider.
    auth.refreshToken();
    throw authFailure; // Let the existing bounded reconnect policy retry.
}
```

`RedisAuthenticationException`, `openConnection`, and
`closeRejectedConnection` represent the corresponding hooks in your client or
connection pool. Forced refresh is recovery from a rejected cached token, not
proof that the IAM user, policy, cache, or region is configured correctly.
Persistent configuration errors should still surface after the client's normal
connection-attempt limit.

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

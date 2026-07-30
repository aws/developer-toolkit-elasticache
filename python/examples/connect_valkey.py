# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Example: bridge the generic token engine into valkey-py.

ElastiCacheIAMAuth is client-agnostic — it only vends get_token(). This example
shows the small bridge you can write in your own code to hook it into valkey-py's
CredentialProvider interface.
"""

import valkey
from valkey.credentials import CredentialProvider

from developer_toolkit_elasticache import ElastiCacheIAMAuth


class ElastiCacheCredentialProvider(CredentialProvider):
    """Customer-owned bridge from the generic token engine to valkey-py."""

    def __init__(self, auth: ElastiCacheIAMAuth):
        self._auth = auth

    def get_credentials(self):
        # valkey-py pulls this on every (re)connection, so the token stays fresh
        return self._auth.user_id, self._auth.get_token()


# serverless_cache_name is the ElastiCache cache NAME — used as the SigV4 signing
# host. It is NOT the connection endpoint DNS below. Signing with the endpoint
# instead of the cache name produces a token the server rejects (WRONGPASS).
# (For a node-based cluster, use replication_group_id="..." instead.)
auth = ElastiCacheIAMAuth(
    serverless_cache_name="my-cache",
    user_id="my-iam-user",
    region="us-east-1",
)

client = valkey.Valkey(
    host="my-cache-abc123.serverless.use1.cache.amazonaws.com",  # connection endpoint (DNS)
    port=6379,
    ssl=True,
    credential_provider=ElastiCacheCredentialProvider(auth),
)

client.set("hello", "world")
print(client.get("hello"))

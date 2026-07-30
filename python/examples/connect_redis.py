# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Example: bridge the generic token engine into redis-py.

ElastiCacheIAMAuth is client-agnostic — it only vends get_token(). This example
shows the small bridge you can write in your own code to hook it into redis-py's
CredentialProvider interface.

"""

import redis
from redis.credentials import CredentialProvider

from developer_toolkit_elasticache import ElastiCacheIAMAuth


class ElastiCacheCredentialProvider(CredentialProvider):
    """Customer-owned bridge from the generic token engine to redis-py."""

    def __init__(self, auth: ElastiCacheIAMAuth):
        self._auth = auth

    def get_credentials(self):
        return self._auth.user_id, self._auth.get_token()


# serverless_cache_name is the Serverless ElastiCache cache name used as the
# SigV4 signing host.
# For a node-based cluster, use replication_group_id="..." instead.
auth = ElastiCacheIAMAuth(
    serverless_cache_name="my-cache",
    user_id="my-iam-user",
    region="us-east-1",
)

client = redis.Redis(
    host="my-cache-abc123.serverless.use1.cache.amazonaws.com",
    port=6379,
    ssl=True,
    credential_provider=ElastiCacheCredentialProvider(auth),
)

client.set("hello", "world")
print(client.get("hello"))

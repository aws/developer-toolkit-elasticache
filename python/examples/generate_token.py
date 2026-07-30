# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Generate a token without connecting to a cache."""

from developer_toolkit_elasticache import generate_iam_auth_token

token = generate_iam_auth_token(
    serverless_cache_name="my-cache",
    user_id="my-user",
    region="us-east-1",
)

print("Token generated successfully!\n")
print(f"Token length: {len(token)} chars")
print(f"Token preview: {token[:80]}...")

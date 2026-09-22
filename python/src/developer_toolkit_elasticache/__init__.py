# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Developer Toolkit for Amazon ElastiCache."""

from developer_toolkit_elasticache.errors import (
    ConfigurationError,
    InvalidParameterError,
    TokenRefreshError,
    ToolkitInputError,
)
from developer_toolkit_elasticache.token_generator import (
    ElastiCacheIAMAuthTokenProvider,
    generate_iam_auth_token,
)
from developer_toolkit_elasticache.token_manager import (
    DEFAULT_REFRESH_AFTER_SECONDS,
    ElastiCacheIAMAuthTokenManager,
)

__all__ = [
    "DEFAULT_REFRESH_AFTER_SECONDS",
    "ConfigurationError",
    "ElastiCacheIAMAuthTokenManager",
    "ElastiCacheIAMAuthTokenProvider",
    "InvalidParameterError",
    "TokenRefreshError",
    "ToolkitInputError",
    "generate_iam_auth_token",
]

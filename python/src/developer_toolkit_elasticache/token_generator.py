# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import os
import re
from typing import TYPE_CHECKING
from urllib.parse import urlencode

from botocore.auth import SigV4QueryAuth
from botocore.awsrequest import AWSRequest
from botocore.session import Session

from developer_toolkit_elasticache.errors import ToolkitUserError

if TYPE_CHECKING:
    from botocore.credentials import ReadOnlyCredentials


_TOKEN_TTL_SECONDS = 900  # 15 minutes

_SIGNING_SERVICE = "elasticache"

# The signed request is a presigned URL; the token is that URL without the scheme.
_URL_SCHEME_PREFIX = "https://"

# The cache name is the SigV4 signing host, validating it keeps a malformed or
# hostile value from being signed against a host and producing a wrong token.
# Matching documented ElastiCache constraints in cache name.
_CACHE_NAME_PATTERN = re.compile(r"^[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*$")

# Honoured on top of botocore's own region resolution — see _resolve_region.
_REGION_ENV_VAR = "AWS_REGION"


class InvalidCacheNameError(ToolkitUserError, ValueError):
    """The cache name is not usable as a SigV4 signing host."""

    def __init__(self, cache_name: str) -> None:
        super().__init__(
            f"Invalid cache name {cache_name!r}: expected letters, digits, and hyphens, "
            "starting with a letter, with no trailing hyphen and no two consecutive "
            "hyphens (the serverless cache name / replication group id)."
        )


class TargetRequiredError(ToolkitUserError, ValueError):
    """Neither or both of the mutually-exclusive cache identifiers were given."""

    def __init__(self) -> None:
        super().__init__(
            "Exactly one of serverless_cache_name or replication_group_id is required."
        )


class RegionNotFoundError(ToolkitUserError, ValueError):
    """No region was passed and none could be resolved from the environment."""

    def __init__(self) -> None:
        super().__init__(
            "No AWS region found. Pass region explicitly, or configure one via "
            "AWS_REGION, AWS_DEFAULT_REGION, or the region setting in your AWS "
            "config profile."
        )


class CredentialsNotFoundError(ToolkitUserError, RuntimeError):
    """The default AWS credential chain did not yield any credentials."""

    def __init__(self) -> None:
        super().__init__(
            "No AWS credentials found. Configure credentials via the environment, "
            "shared config/credentials files, or an instance/container role."
        )


def _validate_cache_name(cache_name: str) -> None:
    if not cache_name or not _CACHE_NAME_PATTERN.match(cache_name):
        raise InvalidCacheNameError(cache_name)


def _resolve_target(
    serverless_cache_name: str | None, replication_group_id: str | None
) -> tuple[str, bool]:
    """Map the mutually-exclusive name arguments to ``(cache_name, serverless)``.

    Exactly one of ``serverless_cache_name`` or ``replication_group_id`` must be
    provided. Which one is used determines the resource type baked into the
    signature
    """
    if serverless_cache_name and not replication_group_id:
        return serverless_cache_name, True
    if replication_group_id and not serverless_cache_name:
        return replication_group_id, False
    raise TargetRequiredError


def _resolve_region(region: str | None, session: Session) -> str:
    """Fall back to a configured region when one was not passed explicitly.

    The session fallback is read from the same session that vends the credentials, so
    the region and the credentials come from the same place — mixing a region from one
    profile with credentials from another yields a token the server rejects
    (``WRONGPASS``) with nothing to indicate the region was the cause.

    ``AWS_REGION`` is consulted directly because botocore does not: its region only
    resolves from ``AWS_DEFAULT_REGION`` or the config profile. The ``aws`` CLI and
    the JS, Go, and Java SDKs all honour ``AWS_REGION`` and give it priority, so
    without this a user whose region is set only there has a working ``aws`` CLI and
    an inexplicably failing toolkit. Checked ahead of the session for the same reason
    botocore ranks the environment above the config file.
    """
    resolved = (
        region or os.environ.get(_REGION_ENV_VAR) or session.get_config_variable("region")
    )
    if not resolved:
        raise RegionNotFoundError
    return resolved


def _resolve_credentials(session: Session) -> "ReadOnlyCredentials":
    """Read a frozen credential set from the session's credential chain.

    Called on every token request rather than cached, so rotated credentials
    (SSO, assume-role, container, instance metadata) are picked up automatically.
    """
    credentials = session.get_credentials()
    if credentials is None:
        raise CredentialsNotFoundError
    return credentials.get_frozen_credentials()


def _sign_token(
    cache_name: str,
    user_id: str,
    region: str,
    credentials: "ReadOnlyCredentials",
    *,
    serverless: bool = True,
) -> str:
    """Core signing logic. Returns the token string.

    ``cache_name`` is the ElastiCache **cache name** (for a serverless cache) or
    the **replication group id** (for a node-based cluster) — the value used as
    the SigV4 signing host. The server recomputes the signature using this name as
    the host, so signing with the endpoint DNS instead produces a token the server
    rejects (``WRONGPASS``).
    Cache names are lowercased at creation time, so the name must be
    signed in lowercase to avoid auth errors.

    ``user_id`` is the ElastiCache **user id** — the value used to construct the
    User ARN referenced in the IAM policy. It is signed as the ``User`` request
    parameter. (For IAM-enabled users this must equal the Redis/Valkey ACL user
    name, but conceptually it is the ElastiCache user id, not the ACL name.)

    ``serverless`` selects the one signing difference between the two IAM-auth
    modes: serverless caches include ``ResourceType=ServerlessCache`` in the
    signed request, node-based replication groups omit ``ResourceType`` entirely.
    It is part of the signature, so it cannot be appended after signing.
    """
    _validate_cache_name(cache_name)

    params = {"Action": "connect", "User": user_id}
    if serverless:
        params["ResourceType"] = "ServerlessCache"
    url = f"{_URL_SCHEME_PREFIX}{cache_name.lower()}/?{urlencode(params)}"

    request = AWSRequest(method="GET", url=url)
    signer = SigV4QueryAuth(
        credentials, _SIGNING_SERVICE, region, expires=_TOKEN_TTL_SECONDS
    )
    signer.add_auth(request)

    return request.url.removeprefix(_URL_SCHEME_PREFIX)


def generate_iam_auth_token(
    *,
    user_id: str,
    region: str | None = None,
    serverless_cache_name: str | None = None,
    replication_group_id: str | None = None,
    session: Session | None = None,
) -> str:
    """Generate an ElastiCache IAM auth token using the default AWS credential chain.

    Provide exactly one of ``serverless_cache_name`` (for a serverless cache) or
    ``replication_group_id`` (for a node-based cluster); the choice selects the
    resource type baked into the signature. ``user_id`` is the ElastiCache user
    id used to build the User ARN in the IAM policy. Returns the token string
    (like RDS's generate_db_auth_token).

    ``region`` defaults to the region the session resolves, so it only needs to be
    passed to sign for a region other than the configured one.

    Pass ``session`` to sign with a specific botocore session; by default a new
    session resolves credentials from the standard chain.
    """
    cache_name, serverless = _resolve_target(serverless_cache_name, replication_group_id)
    session = session or Session()
    resolved_region = _resolve_region(region, session)
    credentials = _resolve_credentials(session)
    return _sign_token(
        cache_name, user_id, resolved_region, credentials, serverless=serverless
    )


class ElastiCacheIAMAuth:
    """Generic IAM token provider for ElastiCache.

    Signs a fresh token on demand each time ``get_token()`` is called, then
    bridges it into whatever client you use (see the ``examples/`` directory).

    Provide exactly one of ``serverless_cache_name`` (for a serverless cache) or
    ``replication_group_id`` (for a node-based cluster); the choice selects the
    resource type baked into the signature.
    ``user_id`` is the ElastiCache user id used to build the User ARN in the IAM policy.
    ``region`` defaults to the region the session resolves, so it only needs to be
    passed to sign for a region other than the configured one.

    Credentials come from the default AWS credential chain (env vars, shared
    config/credentials, or the instance/container role). They are re-read on
    every call, so rotated credentials (SSO, assume-role, container, instance
    metadata) are picked up automatically.
    """

    def __init__(
        self,
        *,
        user_id: str,
        region: str | None = None,
        serverless_cache_name: str | None = None,
        replication_group_id: str | None = None,
        session: Session | None = None,
    ):
        self._cache_name, self._serverless = _resolve_target(
            serverless_cache_name, replication_group_id
        )
        self._user_id = user_id
        self._session = session or Session()
        # Resolved once here rather than per call: unlike credentials, the region is
        # static configuration, and a missing region should surface at construction
        # instead of at the first connection attempt.
        self._region = _resolve_region(region, self._session)

    @property
    def user_id(self) -> str:
        return self._user_id

    def get_token(self) -> str:
        """Sign and return a fresh IAM auth token."""
        credentials = _resolve_credentials(self._session)
        return _sign_token(
            self._cache_name,
            self._user_id,
            self._region,
            credentials,
            serverless=self._serverless,
        )

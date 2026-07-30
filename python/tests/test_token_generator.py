# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

from unittest.mock import MagicMock

import pytest
from botocore.credentials import Credentials
from botocore.session import Session

from developer_toolkit_elasticache import ElastiCacheIAMAuth, generate_iam_auth_token
from developer_toolkit_elasticache.token_generator import (
    CredentialsNotFoundError,
    InvalidCacheNameError,
    RegionNotFoundError,
    TargetRequiredError,
)

CACHE = "my-cache"
USER = "testuser"
REGION = "us-east-1"
# Deliberately different from REGION so a test can tell an explicitly-passed region
# apart from one that fell back to the session.
SESSION_REGION = "eu-west-1"

_DEFAULT_CREDENTIALS = Credentials("FRAIDA1FODNN7EXAMPLE", "secret", "token")
_ROTATED_CREDENTIALS = Credentials("AKIAI44QH8DHBEXAMPLE", "other-secret", "token2")

# Sentinel so callers can ask for "no credentials at all" (None) and still be
# distinguishable from "just give me the default set".
_UNSET = object()


def _fake_session(credentials=_UNSET, region=_UNSET):
    """A botocore session stand-in that vends fixed credentials and a region.

    Every test signs with an injected session, so the suite never touches the real
    AWS credential chain: no environment, config file, or IMDS lookup can leak in.
    Pass ``credentials=None`` to simulate an empty credential chain, or
    ``region=None`` to simulate a session with no region configured.

    The region is stubbed explicitly rather than left to MagicMock's auto-attribute,
    because an auto-created mock is truthy and would let a broken fallback look like
    it resolved a region.
    """
    if credentials is _UNSET:
        credentials = _DEFAULT_CREDENTIALS
    if region is _UNSET:
        region = SESSION_REGION
    session = MagicMock(spec=Session)
    if credentials is None:
        session.get_credentials.return_value = None
    else:
        session.get_credentials.return_value = MagicMock(
            get_frozen_credentials=MagicMock(return_value=credentials)
        )
    session.get_config_variable.side_effect = lambda name: (
        region if name == "region" else None
    )
    return session


@pytest.fixture
def mock_session():
    return _fake_session()


@pytest.fixture(autouse=True)
def no_region_env(monkeypatch):
    """Sever the region environment variables for every test.

    Autouse because AWS_REGION is read from the environment directly, so without this
    the suite's result would depend on the developer's shell: a region exported
    locally would mask the session fallback and quietly pass a broken build. Tests
    that want the env var set it explicitly with monkeypatch.setenv.
    """
    monkeypatch.delenv("AWS_REGION", raising=False)
    monkeypatch.delenv("AWS_DEFAULT_REGION", raising=False)


def _assert_valid_token(token, *, serverless=True):
    assert token is not None
    assert CACHE in token
    assert "Action=connect" in token
    assert f"User={USER}" in token
    assert "X-Amz-Algorithm" in token
    assert "X-Amz-Credential" in token
    assert "X-Amz-Signature" in token
    assert "X-Amz-Expires=900" in token
    # The scheme prefix must be stripped: the token is the presigned URL without it.
    assert not token.startswith("https://")
    # ResourceType is part of the signature and differs per cache kind, so it is
    # the one field that must track the serverless/node-based choice.
    if serverless:
        assert "ResourceType=ServerlessCache" in token
    else:
        assert "ResourceType" not in token


# --- one-shot function -------------------------------------------------------


def test_generate_iam_auth_token(mock_session):
    token = generate_iam_auth_token(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    _assert_valid_token(token)


def test_generate_iam_auth_token_replication_group(mock_session):
    """The one-shot function also supports the replication-group mode."""
    token = generate_iam_auth_token(
        replication_group_id=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    _assert_valid_token(token, serverless=False)


def test_generate_iam_auth_token_requires_a_target(mock_session):
    with pytest.raises(TargetRequiredError):
        generate_iam_auth_token(user_id=USER, region=REGION, session=mock_session)


# --- provider class ----------------------------------------------------------


def test_class_generates_token(mock_session):
    auth = ElastiCacheIAMAuth(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    _assert_valid_token(auth.get_token())


def test_class_exposes_user_id(mock_session):
    auth = ElastiCacheIAMAuth(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    assert auth.user_id == USER


def test_class_no_client_specific_interface(mock_session):
    """The core class must stay client-agnostic — no get_credentials()."""
    auth = ElastiCacheIAMAuth(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    assert not hasattr(auth, "get_credentials")


def test_get_token_signs_on_demand(mock_session):
    """Each call re-reads credentials, so rotated creds are picked up."""
    auth = ElastiCacheIAMAuth(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    auth.get_token()
    auth.get_token()
    assert mock_session.get_credentials.call_count == 2


def test_rotated_credentials_change_the_signature(mock_session):
    """A new credential set must produce a differently-signed token.

    Guards the reason credentials are resolved per call rather than cached.
    """
    auth = ElastiCacheIAMAuth(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    first = auth.get_token()

    mock_session.get_credentials.return_value = MagicMock(
        get_frozen_credentials=MagicMock(return_value=_ROTATED_CREDENTIALS)
    )
    assert auth.get_token() != first


def test_replication_group_omits_resource_type(mock_session):
    """Node-based replication groups sign without ResourceType."""
    auth = ElastiCacheIAMAuth(
        replication_group_id=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    _assert_valid_token(auth.get_token(), serverless=False)


# --- credential handling -----------------------------------------------------


def test_empty_credential_chain_raises():
    """An unconfigured credential chain must fail with a clear, actionable error."""
    auth = ElastiCacheIAMAuth(
        serverless_cache_name=CACHE,
        user_id=USER,
        region=REGION,
        session=_fake_session(credentials=None),
    )
    with pytest.raises(CredentialsNotFoundError):
        auth.get_token()


# --- target validation -------------------------------------------------------


@pytest.mark.parametrize(
    "kwargs",
    [
        {},  # neither name provided
        {"serverless_cache_name": CACHE, "replication_group_id": CACHE},  # both
    ],
)
def test_requires_exactly_one_target(mock_session, kwargs):
    """Exactly one of serverless_cache_name / replication_group_id is required."""
    with pytest.raises(TargetRequiredError):
        ElastiCacheIAMAuth(user_id=USER, region=REGION, session=mock_session, **kwargs)


def test_cache_name_is_lowercased(mock_session):
    """Cache names are lowercased server-side, so signing must lowercase too."""
    auth = ElastiCacheIAMAuth(
        serverless_cache_name="My-Cache",
        user_id=USER,
        region=REGION,
        session=mock_session,
    )
    assert auth.get_token().startswith("my-cache/")


@pytest.mark.parametrize(
    "bad_name",
    [
        "evil.com/path",  # slash / path injection into the signing host
        "my-cache@evil.com",  # userinfo host confusion
        "my-cache/?X=1",  # query injection into the host
        "my cache",  # whitespace
        "my_cache",  # underscore not allowed
    ],
)
def test_invalid_cache_name_rejected(mock_session, bad_name):
    """A malformed/hostile cache name must fail loudly, not sign a wrong host."""
    auth = ElastiCacheIAMAuth(
        serverless_cache_name=bad_name, user_id=USER, region=REGION, session=mock_session
    )
    with pytest.raises(InvalidCacheNameError):
        auth.get_token()


def test_empty_cache_name_rejected_as_missing_target(mock_session):
    """An empty name is indistinguishable from no name, so it fails earlier."""
    with pytest.raises(TargetRequiredError):
        ElastiCacheIAMAuth(
            serverless_cache_name="", user_id=USER, region=REGION, session=mock_session
        )


# --- region resolution -------------------------------------------------------
# The region is part of the SigV4 credential scope, so these assert on the scope in
# the signed token rather than just on the call succeeding: signing with the wrong
# region still produces a valid-looking token that the server rejects.
#
# Precedence is explicit argument > AWS_REGION > session (AWS_DEFAULT_REGION or the
# config profile). AWS_REGION is handled by this package rather than botocore, so it
# needs its own coverage in both directions.

ENV_REGION = "us-west-2"


def test_region_falls_back_to_the_session(mock_session):
    """Omitting region signs with the region the session resolved."""
    token = generate_iam_auth_token(
        serverless_cache_name=CACHE, user_id=USER, session=mock_session
    )
    assert f"%2F{SESSION_REGION}%2F" in token


def test_explicit_region_overrides_the_session():
    """An explicitly passed region wins over the session's configured one."""
    session = _fake_session(region=SESSION_REGION)
    token = generate_iam_auth_token(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=session
    )
    assert f"%2F{REGION}%2F" in token
    assert SESSION_REGION not in token


def test_aws_region_env_var_is_honoured(mock_session, monkeypatch):
    """AWS_REGION resolves a region even though botocore ignores it.

    The aws CLI and the JS/Go/Java SDKs all honour AWS_REGION, so a user whose region
    is set only there would otherwise have a working CLI and a failing toolkit.
    """
    monkeypatch.setenv("AWS_REGION", ENV_REGION)
    session = _fake_session(region=None)
    token = generate_iam_auth_token(
        serverless_cache_name=CACHE, user_id=USER, session=session
    )
    assert f"%2F{ENV_REGION}%2F" in token


def test_aws_region_takes_priority_over_the_session(monkeypatch):
    """Environment beats config file, matching how botocore ranks its own sources."""
    monkeypatch.setenv("AWS_REGION", ENV_REGION)
    session = _fake_session(region=SESSION_REGION)
    token = generate_iam_auth_token(
        serverless_cache_name=CACHE, user_id=USER, session=session
    )
    assert f"%2F{ENV_REGION}%2F" in token
    assert SESSION_REGION not in token


def test_explicit_region_overrides_aws_region_env_var(monkeypatch):
    """An explicit argument is the most specific source, so it wins outright."""
    monkeypatch.setenv("AWS_REGION", ENV_REGION)
    token = generate_iam_auth_token(
        serverless_cache_name=CACHE,
        user_id=USER,
        region=REGION,
        session=_fake_session(region=SESSION_REGION),
    )
    assert f"%2F{REGION}%2F" in token
    assert ENV_REGION not in token


def test_missing_region_raises():
    """No region anywhere is a user-fixable configuration problem."""
    session = _fake_session(region=None)
    with pytest.raises(RegionNotFoundError):
        generate_iam_auth_token(
            serverless_cache_name=CACHE, user_id=USER, session=session
        )


def test_missing_region_error_names_every_source():
    """The message must name each place a region can come from."""
    session = _fake_session(region=None)
    with pytest.raises(RegionNotFoundError, match="AWS_REGION"):
        generate_iam_auth_token(
            serverless_cache_name=CACHE, user_id=USER, session=session
        )
    with pytest.raises(RegionNotFoundError, match="AWS_DEFAULT_REGION"):
        generate_iam_auth_token(
            serverless_cache_name=CACHE, user_id=USER, session=session
        )


def test_class_region_falls_back_to_the_session(mock_session):
    auth = ElastiCacheIAMAuth(
        serverless_cache_name=CACHE, user_id=USER, session=mock_session
    )
    assert f"%2F{SESSION_REGION}%2F" in auth.get_token()


def test_class_missing_region_fails_at_construction():
    """A missing region surfaces immediately, not at the first connection attempt."""
    session = _fake_session(region=None)
    with pytest.raises(RegionNotFoundError):
        ElastiCacheIAMAuth(serverless_cache_name=CACHE, user_id=USER, session=session)

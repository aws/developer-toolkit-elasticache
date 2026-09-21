# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import datetime
from unittest.mock import MagicMock, patch

import pytest
from botocore.credentials import Credentials
from helpers import (
    CACHE,
    REGION,
    ROTATED_CREDENTIALS,
    SESSION_REGION,
    USER,
    fake_session,
)

from developer_toolkit_elasticache import (
    ElastiCacheIAMAuthTokenProvider,
    generate_iam_auth_token,
)
from developer_toolkit_elasticache.errors import (
    ConfigurationError,
    InvalidParameterError,
)


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
    with pytest.raises(InvalidParameterError):
        generate_iam_auth_token(user_id=USER, region=REGION, session=mock_session)


# --- provider class ----------------------------------------------------------


def test_class_generates_token(mock_session):
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    _assert_valid_token(auth.get_token())


def test_class_exposes_user_id(mock_session):
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    assert auth.user_id == USER


def test_class_no_client_specific_interface(mock_session):
    """The core class must stay client-agnostic — no get_credentials()."""
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    assert not hasattr(auth, "get_credentials")


def test_get_token_signs_on_demand(mock_session):
    """Each call re-reads credentials, so rotated creds are picked up."""
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    auth.get_token()
    auth.get_token()
    assert mock_session.get_credentials.call_count == 2


def test_rotated_credentials_change_the_signature(mock_session):
    """A new credential set must produce a differently-signed token.

    Guards the reason credentials are resolved per call rather than cached.
    """
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    first = auth.get_token()

    mock_session.get_credentials.return_value = MagicMock(
        get_frozen_credentials=MagicMock(return_value=ROTATED_CREDENTIALS)
    )
    assert auth.get_token() != first


def test_replication_group_omits_resource_type(mock_session):
    """Node-based replication groups sign without ResourceType."""
    auth = ElastiCacheIAMAuthTokenProvider(
        replication_group_id=CACHE, user_id=USER, region=REGION, session=mock_session
    )
    _assert_valid_token(auth.get_token(), serverless=False)


# --- credential handling -----------------------------------------------------


def test_empty_credential_chain_raises():
    """An unconfigured credential chain must fail with a clear, actionable error."""
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name=CACHE,
        user_id=USER,
        region=REGION,
        session=fake_session(credentials=None),
    )
    with pytest.raises(ConfigurationError, match="credentials"):
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
    with pytest.raises(InvalidParameterError):
        ElastiCacheIAMAuthTokenProvider(
            user_id=USER, region=REGION, session=mock_session, **kwargs
        )


def test_cache_name_is_lowercased(mock_session):
    """Cache names are lowercased server-side, so signing must lowercase too."""
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name="My-Cache",
        user_id=USER,
        region=REGION,
        session=mock_session,
    )
    assert auth.get_token().startswith("my-cache/")


def test_user_id_is_lowercased_consistently(mock_session):
    """User ids are stored lowercase server-side, so both the signed token and the
    username exposed to the client bridge must be lowercase — and must agree, or
    AUTH sends a username/token pair for different users and fails as WRONGPASS.
    """
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name=CACHE,
        user_id="MixedCaseUser",
        region=REGION,
        session=mock_session,
    )
    assert auth.user_id == "mixedcaseuser"
    assert "User=mixedcaseuser" in auth.get_token()
    assert "MixedCaseUser" not in auth.get_token()


@pytest.mark.parametrize(
    "bad_name",
    [
        "evil.com/path",  # slash / path injection into the signing host
        "my-cache@evil.com",  # userinfo host confusion
        "my-cache/?X=1",  # query injection into the host
        "my cache",  # whitespace
        "my_cache",  # underscore not allowed
        "1-cache",  # must start with a letter, not a digit
        "-cache",  # must start with a letter, not a hyphen
        "cache-",  # cannot end with a hyphen
        "my--cache",  # cannot contain two consecutive hyphens
        "-",  # a bare hyphen is neither
    ],
)
def test_invalid_cache_name_rejected(mock_session, bad_name):
    """A malformed/hostile cache name must fail loudly, not sign a wrong host.

    The error names the parameter that was bad, since the type no longer does.
    """
    with pytest.raises(InvalidParameterError, match="serverless_cache_name"):
        ElastiCacheIAMAuthTokenProvider(
            serverless_cache_name=bad_name,
            user_id=USER,
            region=REGION,
            session=mock_session,
        )


def test_invalid_replication_group_id_names_that_parameter(mock_session):
    """A bad replication_group_id is reported under its own parameter name."""
    with pytest.raises(InvalidParameterError, match="replication_group_id"):
        ElastiCacheIAMAuthTokenProvider(
            replication_group_id="bad_name!",
            user_id=USER,
            region=REGION,
            session=mock_session,
        )


def test_error_never_echoes_the_caller_value(mock_session):
    hostile = "evil.com/inject\nlog-entry"
    with pytest.raises(InvalidParameterError) as excinfo:
        ElastiCacheIAMAuthTokenProvider(
            serverless_cache_name=hostile,
            user_id=USER,
            region=REGION,
            session=mock_session,
        )
    assert hostile not in str(excinfo.value)
    assert "serverless_cache_name" in str(excinfo.value)


@pytest.mark.parametrize(
    "good_name",
    [
        "c",  # a single letter is the shortest legal name
        "my-cache",
        "cache1",
        "a-1-b-2",  # hyphens between alphanumerics, repeatedly
        "MyCache",  # signed lowercase, but accepted as given
    ],
)
def test_valid_cache_name_accepted(mock_session, good_name):
    """Tightening the pattern must not reject names the service accepts."""
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name=good_name, user_id=USER, region=REGION, session=mock_session
    )
    assert auth.get_token().startswith(f"{good_name.lower()}/")


@pytest.mark.parametrize(
    "bad_user_id",
    [
        "",  # empty
        "1user",  # must start with a letter
        "-user",  # must start with a letter, not a hyphen
        "user name",  # whitespace
        "user@host",  # userinfo-style value
        "user.name",  # a dot is only allowed via the "default." prefix
        "default.",  # nothing after the prefix
        "default.1x",  # label after the prefix must start with a letter
        "notdefault.foo",  # the prefix must be literally "default."
    ],
)
def test_invalid_user_id_rejected(mock_session, bad_user_id):
    """A malformed user id fails loudly instead of signing a WRONGPASS token."""
    with pytest.raises(InvalidParameterError, match="user_id"):
        ElastiCacheIAMAuthTokenProvider(
            serverless_cache_name=CACHE,
            user_id=bad_user_id,
            region=REGION,
            session=mock_session,
        )


@pytest.mark.parametrize(
    "good_user_id",
    [
        "iam-user",
        "myuser",
        "default",  # service-managed default user
        "default.iam-user",  # service-managed IAM user
        "default.other",  # any default.* is accepted
        "DEFAULT.IAM-USER",  # case-normalized before matching
        "a-1-b-2",
    ],
)
def test_valid_user_id_accepted(mock_session, good_user_id):
    """Validation must not reject user ids the service accepts.

    Case does not matter — the id is lowercased before both matching and signing —
    so the signed User value is the lowercased form regardless of input case.
    """
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name=CACHE,
        user_id=good_user_id,
        region=REGION,
        session=mock_session,
    )
    assert f"User={good_user_id.lower()}" in auth.get_token()


def test_configuration_error_is_not_a_parameter_error():
    """Missing region/credentials is a configuration failure, not a bad parameter."""
    assert not issubclass(ConfigurationError, InvalidParameterError)


def test_empty_cache_name_rejected_as_missing_target(mock_session):
    """An empty name is indistinguishable from no name, so it fails earlier."""
    with pytest.raises(InvalidParameterError):
        ElastiCacheIAMAuthTokenProvider(
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
    session = fake_session(region=SESSION_REGION)
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
    session = fake_session(region=None)
    token = generate_iam_auth_token(
        serverless_cache_name=CACHE, user_id=USER, session=session
    )
    assert f"%2F{ENV_REGION}%2F" in token


def test_aws_region_takes_priority_over_the_session(monkeypatch):
    """Environment beats config file, matching how botocore ranks its own sources."""
    monkeypatch.setenv("AWS_REGION", ENV_REGION)
    session = fake_session(region=SESSION_REGION)
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
        session=fake_session(region=SESSION_REGION),
    )
    assert f"%2F{REGION}%2F" in token
    assert ENV_REGION not in token


def test_missing_region_raises():
    """No region anywhere is a user-fixable configuration problem."""
    session = fake_session(region=None)
    with pytest.raises(ConfigurationError, match="region"):
        generate_iam_auth_token(
            serverless_cache_name=CACHE, user_id=USER, session=session
        )


def test_missing_region_error_names_every_source():
    """The message must name each place a region can come from."""
    session = fake_session(region=None)
    with pytest.raises(ConfigurationError, match="AWS_REGION"):
        generate_iam_auth_token(
            serverless_cache_name=CACHE, user_id=USER, session=session
        )
    with pytest.raises(ConfigurationError, match="AWS_DEFAULT_REGION"):
        generate_iam_auth_token(
            serverless_cache_name=CACHE, user_id=USER, session=session
        )


def test_class_region_falls_back_to_the_session(mock_session):
    auth = ElastiCacheIAMAuthTokenProvider(
        serverless_cache_name=CACHE, user_id=USER, session=mock_session
    )
    assert f"%2F{SESSION_REGION}%2F" in auth.get_token()


def test_class_missing_region_fails_at_construction():
    """A missing region surfaces immediately, not at the first connection attempt."""
    session = fake_session(region=None)
    with pytest.raises(ConfigurationError, match="region"):
        ElastiCacheIAMAuthTokenProvider(
            serverless_cache_name=CACHE, user_id=USER, session=session
        )


# --- known-answer signature tests --------------------------------------------
#
# The tests above check the token's structure. These check its exact value,
# including the SigV4 signature, so a change that silently altered the signing
# inputs (service name, canonical request, credential scope) would fail here
# rather than only at connect time against a real cache.
#
# SigV4 signatures depend on the request timestamp, so the clock botocore reads
# (botocore.auth.get_current_datetime) is frozen to make the signature
# reproducible. These are fake credentials well-known AWS documentation example
# keys.
# https://docs.aws.amazon.com/sdkref/latest/guide/feature-static-credentials.html

_KAT_CREDENTIALS = Credentials(
    "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
)
_KAT_INSTANT = datetime.datetime(2025, 1, 1, 0, 0, 0, tzinfo=datetime.timezone.utc)
_KAT_REGION = "us-east-1"
_KAT_CACHE = "my-cache"
_KAT_USER = "testuser"


def _kat_session():
    return fake_session(credentials=_KAT_CREDENTIALS, region=_KAT_REGION)


def _generate_at_fixed_time(**kwargs):
    """Generate a token with botocore's signing clock pinned to _KAT_INSTANT."""
    with patch("botocore.auth.get_current_datetime", return_value=_KAT_INSTANT):
        return generate_iam_auth_token(
            user_id=_KAT_USER, region=_KAT_REGION, session=_kat_session(), **kwargs
        )


def test_serverless_token_matches_known_answer():
    """The full serverless token, signature included, matches a pinned value."""
    token = _generate_at_fixed_time(serverless_cache_name=_KAT_CACHE)
    assert token == (
        "my-cache/?Action=connect&User=testuser&ResourceType=ServerlessCache"
        "&X-Amz-Algorithm=AWS4-HMAC-SHA256"
        "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20250101%2Fus-east-1"
        "%2Felasticache%2Faws4_request"
        "&X-Amz-Date=20250101T000000Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host"
        "&X-Amz-Signature="
        "28f349fd92f74e0192de149a74743ae7d087bb136523c342bdd67632a8360023"
    )


def test_replication_group_token_matches_known_answer():
    """The node-based token omits ResourceType and therefore signs differently."""
    token = _generate_at_fixed_time(replication_group_id=_KAT_CACHE)
    assert "ResourceType" not in token
    assert token == (
        "my-cache/?Action=connect&User=testuser"
        "&X-Amz-Algorithm=AWS4-HMAC-SHA256"
        "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20250101%2Fus-east-1"
        "%2Felasticache%2Faws4_request"
        "&X-Amz-Date=20250101T000000Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host"
        "&X-Amz-Signature="
        "cd78a7de74c1bb1c8c685cac428f783b5433ce5fbf569af867570311d6a85797"
    )


def test_known_answer_signatures_differ_by_resource_type():
    """Serverless and node-based must not produce the same signature.

    The only difference in the signed request is ResourceType, so identical
    signatures would mean it was dropped from the signature — the exact bug the
    serverless/node-based split exists to prevent.
    """
    serverless = _generate_at_fixed_time(serverless_cache_name=_KAT_CACHE)
    node_based = _generate_at_fixed_time(replication_group_id=_KAT_CACHE)
    assert serverless != node_based

# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Test doubles shared by the token generator and token manager suites."""

from unittest.mock import MagicMock

from botocore.credentials import Credentials
from botocore.session import Session

CACHE = "my-cache"
USER = "testuser"
REGION = "us-east-1"
SESSION_REGION = "eu-west-1"

DEFAULT_CREDENTIALS = Credentials("FRAIDA1FODNN7EXAMPLE", "secret", "token")
ROTATED_CREDENTIALS = Credentials("AKIAI44QH8DHBEXAMPLE", "other-secret", "token2")

_UNSET = object()


def fake_session(credentials=_UNSET, region=_UNSET):
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
        credentials = DEFAULT_CREDENTIALS
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

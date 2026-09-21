# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import pytest
from helpers import fake_session


@pytest.fixture
def mock_session():
    return fake_session()


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

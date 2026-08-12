# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse

import pytest

from developer_toolkit_elasticache import cli
from developer_toolkit_elasticache.errors import InvalidParameterError, ToolkitInputError

CACHE = "my-cache"
USER = "testuser"
REGION = "us-east-1"

_BASE_ARGV = [
    "generate_iam_auth_token",
    "--serverless-cache-name",
    CACHE,
    "--user-id",
    USER,
    "--region",
    REGION,
]


@pytest.fixture
def fake_token(monkeypatch):
    """Stub out signing so the CLI tests never touch the AWS credential chain."""
    calls = {}

    def _generate(**kwargs):
        calls.update(kwargs)
        return "signed-token"

    monkeypatch.setattr(cli, "generate_iam_auth_token", _generate)
    return calls


def test_prints_token_to_stdout(capsys, fake_token):
    """The bare token must be the only thing on stdout so $(...) can capture it."""
    assert cli.main(_BASE_ARGV) == 0
    captured = capsys.readouterr()
    assert captured.out == "signed-token\n"
    assert captured.err == ""


def test_passes_arguments_through(fake_token):
    cli.main(_BASE_ARGV)
    assert fake_token == {
        "user_id": USER,
        "region": REGION,
        "serverless_cache_name": CACHE,
        "replication_group_id": None,
    }


def test_region_is_optional_and_passed_as_none(fake_token):
    """Omitting --region defers to the library's session fallback.

    The CLI must forward None rather than resolving a region itself, so that both the
    CLI and library paths resolve it from the same session that vends the credentials.
    """
    assert (
        cli.main(
            [
                "generate_iam_auth_token",
                "--serverless-cache-name",
                CACHE,
                "--user-id",
                USER,
            ]
        )
        == 0
    )
    assert fake_token["region"] is None


def test_replication_group_mode(fake_token):
    cli.main(
        [
            "generate_iam_auth_token",
            "--replication-group-id",
            CACHE,
            "--user-id",
            USER,
            "--region",
            REGION,
        ]
    )
    assert fake_token["replication_group_id"] == CACHE
    assert fake_token["serverless_cache_name"] is None


def test_expected_error_is_a_message_not_a_traceback(capsys, monkeypatch):
    """A user mistake exits 1 with one line on stderr and nothing on stdout."""

    def _raise(**_kwargs):
        raise InvalidParameterError(
            "Invalid value ('bad name') for parameter 'serverless_cache_name': nope"
        )

    monkeypatch.setattr(cli, "generate_iam_auth_token", _raise)

    assert cli.main(_BASE_ARGV) == 1
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err.startswith("Error: ")
    assert "bad name" in captured.err


def test_unexpected_error_propagates(monkeypatch):
    """An unexpected failure keeps its traceback rather than being flattened."""

    def _raise(**_kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(cli, "generate_iam_auth_token", _raise)

    with pytest.raises(RuntimeError, match="boom"):
        cli.main(_BASE_ARGV)


def _register_extra_tool(monkeypatch, func):
    """Add a second subcommand named ``extra`` the way a real new tool would be.

    These tests cover the contract ``main`` offers to future tools — dispatch via
    ``func``, conditional stdout, ``ToolkitInputError`` handling — so that adding a
    tool cannot silently inherit wrong behaviour.
    """

    def _build():
        parser = argparse.ArgumentParser(prog="developer-toolkit-elasticache")
        subparsers = parser.add_subparsers(dest="command", required=True)
        extra = subparsers.add_parser("extra")
        extra.set_defaults(func=func)
        return parser

    monkeypatch.setattr(cli, "_build_parser", _build)


def test_command_returning_none_prints_nothing(capsys, monkeypatch):
    """A tool with no output must not print a bare "None" or a blank line."""
    _register_extra_tool(monkeypatch, lambda _args: None)

    assert cli.main(["extra"]) == 0
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == ""


def test_new_tools_user_error_is_a_message_not_a_traceback(capsys, monkeypatch):
    """Deriving from ToolkitInputError is all a new tool needs for clean reporting."""

    class ExtraToolError(ToolkitInputError):
        pass

    def _raise(_args):
        raise ExtraToolError("cannot do that")

    _register_extra_tool(monkeypatch, _raise)

    assert cli.main(["extra"]) == 1
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == "Error: cannot do that\n"


@pytest.mark.parametrize(
    "argv",
    [
        [],  # no subcommand
        ["generate_iam_auth_token", "--user-id", USER, "--region", REGION],  # no target
        [  # both targets
            "generate_iam_auth_token",
            "--serverless-cache-name",
            CACHE,
            "--replication-group-id",
            CACHE,
            "--user-id",
            USER,
            "--region",
            REGION,
        ],
        ["generate_iam_auth_token", "--serverless-cache-name", CACHE],  # missing required
    ],
)
def test_argument_errors_exit_2(argv):
    """argparse rejects malformed invocations before any signing happens."""
    with pytest.raises(SystemExit) as excinfo:
        cli.main(argv)
    assert excinfo.value.code == 2

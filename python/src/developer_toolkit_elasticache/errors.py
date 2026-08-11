# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Error types raised by the toolkit.

``ToolkitUserError`` is the base for every failure the user can correct. It is
also the contract with the CLI: a ``ToolkitUserError`` is reported as a
single-line message instead of a traceback, and anything else is treated as a
bug in the toolkit and keeps its traceback.
"""


class ToolkitUserError(Exception):
    """Base class for failures the user can correct."""


class InvalidParameterError(ToolkitUserError, ValueError):
    """A parameter was given a value that is not valid.

    Error type for every 'bad value' case. The parameter name and the
    offending value are carried in the message
    """

    def __init__(self, parameter: str, value: str, reason: str) -> None:
        super().__init__(
            f"Invalid value ({value!r}) for parameter {parameter!r}: {reason}"
        )


class TargetRequiredError(ToolkitUserError, ValueError):
    """Neither or both of the mutually-exclusive cache identifiers were given."""

    def __init__(self) -> None:
        super().__init__(
            "Exactly one of serverless_cache_name or replication_group_id is required."
        )


class ConfigurationError(ToolkitUserError):
    """The AWS environment is missing required values to sign a token.

    Covers the missing-configuration cases (no region or no credentials).
    """

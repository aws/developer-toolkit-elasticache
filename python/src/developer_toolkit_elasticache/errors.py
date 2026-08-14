# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Error types raised by the toolkit.

``ToolkitInputError`` is the base for every failure the user can correct. It is also
the contract with the CLI: a ``ToolkitInputError`` is reported as a single-line
message instead of a traceback, and anything else is treated as a bug in the
toolkit and keeps its traceback.
"""


class ToolkitInputError(Exception):
    """Base class for failures the user can correct.

    Catch this in CLI or application code to handle all user-fixable problems
    uniformly (display a message, exit non-zero) without coupling to a specific
    subclass.
    """


class InvalidParameterError(ToolkitInputError, ValueError):
    """A parameter value or combination of parameters is not valid.

    Covers both single-value violations (bad format, out of range) and
    multi-parameter constraint violations (mutually-exclusive arguments,
    missing required combinations).
    """


class ConfigurationError(ToolkitInputError):
    """The AWS environment is missing required values to sign a token.

    Covers the missing-configuration cases (no region or no credentials).
    """

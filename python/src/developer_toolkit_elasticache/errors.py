# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""Error types shared by every tool in the toolkit.

``ToolkitUserError`` is the contract between the tools and the CLI indicating user input error
and the CLI reports it as a single-line message instead of a traceback.
Anything else is treated as a bug in the toolkit and keeps its traceback

"""


class ToolkitUserError(Exception):
    """Base class for failures the user can correct."""

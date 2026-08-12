# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import sys

from developer_toolkit_elasticache import generate_iam_auth_token
from developer_toolkit_elasticache.errors import ToolkitInputError


def _add_generate_iam_auth_token_args(parser: argparse.ArgumentParser) -> None:
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument(
        "--serverless-cache-name",
        help="Serverless cache name (the SigV4 signing host)",
    )
    target.add_argument(
        "--replication-group-id",
        help="Replication group id for a node-based cluster (the SigV4 signing host)",
    )
    parser.add_argument(
        "--user-id",
        required=True,
        help="ElastiCache user id (used to build the User ARN in the IAM policy)",
    )
    parser.add_argument(
        "--region",
        help="AWS region (default: the region resolved from your AWS configuration)",
    )


def _cmd_generate_iam_auth_token(args: argparse.Namespace) -> str | None:
    return generate_iam_auth_token(
        user_id=args.user_id,
        region=args.region,
        serverless_cache_name=args.serverless_cache_name,
        replication_group_id=args.replication_group_id,
    )


def _build_parser() -> argparse.ArgumentParser:
    """Build the top-level parser.

    Each tool contributes one subparser and sets ``func`` to a callable taking the
    parsed namespace. That callable returns the text to write to stdout, or ``None``
    if the tool has nothing to print. It signals a user-fixable failure by raising
    an ``ToolkitInputError`` subclass; ``main`` handles the rest, so adding a tool
    means adding a subparser here and nothing else.
    """
    parser = argparse.ArgumentParser(
        prog="developer-toolkit-elasticache",
        description="Developer tools for Amazon ElastiCache",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    gen = subparsers.add_parser(
        "generate_iam_auth_token",
        help="Generate an IAM auth token for an ElastiCache cache",
    )
    _add_generate_iam_auth_token_args(gen)
    gen.set_defaults(func=_cmd_generate_iam_auth_token)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)

    try:
        output = args.func(args)
    except ToolkitInputError as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    if output is not None:
        print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())

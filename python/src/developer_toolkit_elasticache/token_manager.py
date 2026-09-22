# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

"""A cached, self-refreshing IAM auth token for Amazon ElastiCache.

``ElastiCacheIAMAuthTokenManager`` wraps the stateless provider with a cache that is
refreshed in the background, so callers always receive a valid token without signing
on every call.
"""

import logging
import math
import random
import threading
import time
from collections.abc import Callable
from concurrent.futures import Future
from dataclasses import dataclass, field
from typing import Protocol

from botocore.session import Session

from developer_toolkit_elasticache.errors import InvalidParameterError, TokenRefreshError
from developer_toolkit_elasticache.token_generator import (
    _TOKEN_TTL_SECONDS,
    ElastiCacheIAMAuthTokenProvider,
)

_logger = logging.getLogger(__name__)

# Stop serving a token this long before its 900s lifetime ends, to absorb clock skew
# and the AUTH round trip.
_SERVE_MARGIN_SECONDS = 20
_EFFECTIVE_LIFETIME_SECONDS = _TOKEN_TTL_SECONDS - _SERVE_MARGIN_SECONDS

# A third of the token lifetime: two refresh windows remain if this one fails.
DEFAULT_REFRESH_AFTER_SECONDS = 300

# Retry policy for one refresh cycle
_MAX_ATTEMPTS = 8
_BASE_DELAY_SECONDS = 0.1
_MAX_DELAY_SECONDS = 5.0
_JITTER_RATIO = 0.2

_CLOSED_MESSAGE = "The token manager is closed."
_REFRESH_FAILED_MESSAGE = (
    "Could not refresh the ElastiCache IAM authentication token before the previous "
    "token expired."
)
_REPLACEMENT_FAILED_MESSAGE = (
    "Could not sign a replacement ElastiCache IAM authentication token."
)

# Module attributes so tests can substitute the clock, scheduler, jitter, and sleep.
# Wall clock, not monotonic: the server judges the token by X-Amz-Date against its own
# wall clock, and a monotonic clock stands still while the host is suspended.
_now = time.time
_random = random.random  # jitter only, not security-sensitive


def _wait(stop: threading.Event, seconds: float) -> None:
    """Sleep between retries; returns early once ``stop`` is set by close()."""
    stop.wait(seconds)


class _ScheduledCall(Protocol):
    def cancel(self) -> None: ...


def _schedule(delay: float, callback: Callable[[], None]) -> _ScheduledCall:
    """Run ``callback`` after ``delay`` seconds on a daemon thread."""
    timer = threading.Timer(delay, callback)
    timer.daemon = True
    timer.name = "elasticache-iam-token-refresh"
    timer.start()
    return timer


@dataclass(frozen=True)
class _CachedToken:
    token: str = field(repr=False)  # bearer secret: keep it out of dumps of locals
    issued_at: float
    expires_at: float


def _validate_refresh_after(value: object) -> float:
    valid = (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
        and 0 < value < _EFFECTIVE_LIFETIME_SECONDS
    )
    if not valid:
        raise InvalidParameterError(
            "Invalid value for parameter 'refresh_after': must be greater than 0 "
            f"and less than {_EFFECTIVE_LIFETIME_SECONDS}."
        )
    return float(value)


class ElastiCacheIAMAuthTokenManager:
    """Cache an ElastiCache IAM auth token and refresh it in the background.

    Takes the provider's arguments; configuration is validated at construction and
    the first token is signed lazily. A replacement is signed ``refresh_after``
    seconds after each token (default 300; must be below the 880s effective lifetime,
    which is the 900s token lifetime minus a 20s serve margin) while callers keep
    receiving the current one. ``on_token_changed`` is called synchronously
    with each new token; its failures are logged and otherwise ignored.

    ``refresh_token()`` signs a replacement immediately, for a client whose ``AUTH``
    was rejected with a token this manager still considers valid.

    Thread-safe: callers with no valid token share one signing cycle. Call
    ``close()`` (or use as a context manager) when done; later calls raise
    ``TokenRefreshError``.
    """

    def __init__(  # noqa: PLR0913  # the provider's keyword-only arguments plus manager options
        self,
        *,
        user_id: str,
        region: str | None = None,
        serverless_cache_name: str | None = None,
        replication_group_id: str | None = None,
        session: Session | None = None,
        refresh_after: float = DEFAULT_REFRESH_AFTER_SECONDS,
        on_token_changed: Callable[[str], None] | None = None,
    ):
        self._provider = ElastiCacheIAMAuthTokenProvider(
            user_id=user_id,
            region=region,
            serverless_cache_name=serverless_cache_name,
            replication_group_id=replication_group_id,
            session=session,
        )
        self._refresh_after = _validate_refresh_after(refresh_after)
        self._on_token_changed = on_token_changed
        # Guards the fields below; never held while signing or sleeping.
        self._lock = threading.Lock()
        self._cached: _CachedToken | None = None
        self._closed = False
        self._stop = threading.Event()
        self._inflight: Future[str] | None = None  # the signing cycle in progress
        self._scheduled: _ScheduledCall | None = None
        self._refresh_due_at = math.inf  # in the past = missed timer, refresh now
        self._refreshing = False

    @property
    def user_id(self) -> str:
        return self._provider.user_id

    def get_credentials(self) -> tuple[str, str]:
        """Return ``(user_id, token)`` for clients that take a credentials callable."""
        return self.user_id, self.get_token()

    def get_token(self) -> str:
        """Return a valid token, signing one only if the cache is empty or expired.

        Blocks for the signing cycle (retries included) only when no valid token is
        cached. Raises ``TokenRefreshError`` if the cached token expired before a
        refresh replaced it, or after ``close()``; a first-token failure raises the
        underlying error.
        """
        with self._lock:
            self._ensure_open()
            cached = self._cached
            now = _now()
            if cached is not None and now < cached.expires_at:
                if now >= self._refresh_due_at:
                    self._start_background_refresh_locked(cached)
                return cached.token
        return self._refresh(reuse_unless=None)

    def refresh_token(self) -> str:
        """Sign a replacement token now and return it.

        Use after the server rejected the current token (for example because the
        signing credentials were revoked). Joins a signing cycle already in progress
        instead of starting another. Raises ``TokenRefreshError`` if no replacement
        could be signed, or after ``close()``.
        """
        with self._lock:
            self._ensure_open()
            current = self._cached
        token = self._refresh(reuse_unless=current)
        with self._lock:
            replaced = self._cached is not None and self._cached is not current
        if not replaced:
            raise TokenRefreshError(_REPLACEMENT_FAILED_MESSAGE)
        return token

    def close(self) -> None:
        """Stop background refresh and drop the cached token. Idempotent."""
        with self._lock:
            self._closed = True
            self._cached = None
            scheduled, self._scheduled = self._scheduled, None
        self._stop.set()
        if scheduled is not None:
            scheduled.cancel()

    def __enter__(self) -> "ElastiCacheIAMAuthTokenManager":  # noqa: PYI034  # typing.Self is 3.11+; the package supports 3.10
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()

    def _ensure_open(self) -> None:
        if self._closed:
            raise TokenRefreshError(_CLOSED_MESSAGE)

    def _refresh(self, *, reuse_unless: "_CachedToken | None") -> str:
        """Sign a new token, unless a valid one other than ``reuse_unless`` is cached.

        ``reuse_unless`` is the token the caller wants replaced (``None`` to accept any
        valid token). Joins the signing cycle already in progress, if any, and shares
        its outcome.
        """
        with self._lock:
            self._ensure_open()
            cached = self._cached
            if (
                cached is not None
                and cached is not reuse_unless
                and _now() < cached.expires_at
            ):
                return cached.token
            flight = self._inflight
            owner = flight is None
            if owner:
                flight = self._inflight = Future()
        if not owner:
            return flight.result()
        try:
            token, installed = self._run_refresh_cycle()
        except BaseException as error:
            flight.set_exception(error)
            raise
        else:
            flight.set_result(token)
        finally:
            with self._lock:
                if self._inflight is flight:
                    self._inflight = None
        # Only after the flight is closed: a refresh timer that fires now starts its own
        # cycle instead of joining this one and skipping the reschedule, and a slow
        # callback cannot hold the flight open.
        if installed:
            with self._lock:
                if not self._closed and self._cached is not None:
                    self._schedule_locked(self._refresh_after, self._cached)
            self._notify(token)
        return token

    def _run_refresh_cycle(self) -> tuple[str, bool]:
        """Sign with retries; returns ``(token, newly_installed)``.

        Every error is retried: on an instance role a transient IMDS failure surfaces
        as "no credentials", so even ``ConfigurationError`` may be transient. On
        failure: return a still-valid cached token and reschedule, raise the last
        error if nothing is cached, or raise ``TokenRefreshError`` if it expired.
        """
        last_error: Exception | None = None
        for attempt in range(1, _MAX_ATTEMPTS + 1):
            self._raise_if_closed(last_error)
            try:
                issued_at = _now()
                token = self._provider.get_token()
            except Exception as error:  # noqa: BLE001  # see docstring
                last_error = error
                if attempt < _MAX_ATTEMPTS:
                    _wait(self._stop, self._backoff_delay(attempt))
                continue
            with self._lock:
                self._ensure_open()
                self._install_locked(token, issued_at)
            return token, True

        remaining = 0.0
        with self._lock:
            self._raise_if_closed(last_error)
            cached = self._cached
            if cached is not None and _now() < cached.expires_at:
                self._schedule_locked(_MAX_DELAY_SECONDS, cached)
                remaining = cached.expires_at - _now()
        if cached is not None and remaining > 0:
            _logger.warning(
                "Could not refresh the ElastiCache IAM auth token after %d attempts "
                "(%s: %s); serving the current token for another %.0fs and retrying "
                "in %.0fs.",
                _MAX_ATTEMPTS,
                type(last_error).__name__,
                last_error,
                remaining,
                _MAX_DELAY_SECONDS,
            )
            # Re-check: the token may have expired while the lock was released, and a
            # waiter that joined because its token expired must not get it back.
            with self._lock:
                self._raise_if_closed(last_error)
                if _now() < cached.expires_at:
                    return cached.token, False
        if cached is None and last_error is not None:
            raise last_error
        raise TokenRefreshError(_REFRESH_FAILED_MESSAGE) from last_error

    def _notify(self, token: str) -> None:
        if self._on_token_changed is None:
            return
        try:
            self._on_token_changed(token)
        except Exception as error:  # noqa: BLE001  # a callback failure must not discard the token
            # Type only: the callback received the token, so its message might too.
            _logger.warning("on_token_changed callback raised %s", type(error).__name__)

    def _raise_if_closed(self, cause: Exception | None) -> None:
        if self._closed:
            raise TokenRefreshError(_CLOSED_MESSAGE) from cause

    @staticmethod
    def _backoff_delay(failed_attempt: int) -> float:
        delay = min(_BASE_DELAY_SECONDS * 2 ** (failed_attempt - 1), _MAX_DELAY_SECONDS)
        jitter = 1 - _JITTER_RATIO + 2 * _JITTER_RATIO * _random()
        return delay * jitter

    def _install_locked(self, token: str, issued_at: float) -> None:
        """Cache the token. Its refresh timer is started by ``_refresh`` once the
        flight is closed; until then the due time is set so nobody treats the gap as
        a missed timer."""
        self._cached = _CachedToken(
            token=token,
            issued_at=issued_at,
            expires_at=issued_at + _EFFECTIVE_LIFETIME_SECONDS,
        )
        self._refresh_due_at = _now() + self._refresh_after

    def _schedule_locked(self, delay: float, due: _CachedToken) -> None:
        """Schedule a background refresh of ``due``; on failure, leave it to get_token()."""
        previous, self._scheduled = self._scheduled, None
        if previous is not None:
            previous.cancel()
        try:
            self._scheduled = _schedule(delay, lambda: self._background_refresh(due))
        except Exception:  # noqa: BLE001  # e.g. RuntimeError: can't start new thread
            self._refresh_due_at = -math.inf
            self._refreshing = False
            return
        self._refresh_due_at = _now() + delay

    def _start_background_refresh_locked(self, due: _CachedToken) -> None:
        if not self._refreshing:
            self._refreshing = True
            self._schedule_locked(0, due)

    def _background_refresh(self, due: _CachedToken) -> None:
        """Replace ``due`` with a fresh token. Runs on the scheduler's thread."""
        with self._lock:
            if self._closed:
                self._refreshing = False
                return
            self._refreshing = True
        try:
            # A cycle that fails while the token is valid logs, reschedules, and
            # returns. It only raises once the token has expired; the next get_token()
            # surfaces that to a caller, so nothing is lost by not re-raising here.
            self._refresh(reuse_unless=due)
        except Exception as error:  # noqa: BLE001  # a timer thread has nobody to raise to
            _logger.warning(
                "Background refresh of the ElastiCache IAM auth token failed and the "
                "cached token has expired (%s: %s).",
                type(error).__name__,
                error,
            )
        finally:
            with self._lock:
                self._refreshing = False

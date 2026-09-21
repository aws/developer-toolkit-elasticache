# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

import threading
import time

import pytest
from helpers import CACHE, REGION, ROTATED_CREDENTIALS, USER, fake_session

import developer_toolkit_elasticache as package
from developer_toolkit_elasticache import token_manager
from developer_toolkit_elasticache.errors import (
    ConfigurationError,
    InvalidParameterError,
    TokenRefreshError,
    ToolkitInputError,
)
from developer_toolkit_elasticache.token_manager import (
    DEFAULT_REFRESH_AFTER_SECONDS,
    ElastiCacheIAMAuthTokenManager,
)

TOKEN_TTL = 900
SERVE_MARGIN = 20
EFFECTIVE_LIFETIME = TOKEN_TTL - SERVE_MARGIN  # 880: served this long after signing
MAX_ATTEMPTS = 8
MAX_DELAY = 5.0

# Captured before any fixture patches them, for the tests of the real implementations.
REAL_SCHEDULE = token_manager._schedule
REAL_WAIT = token_manager._wait


class FakeClock:
    """A wall clock the test advances by hand."""

    def __init__(self, start=1_000.0):
        self.now = start

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


@pytest.fixture
def clock(monkeypatch):
    fake = FakeClock()
    monkeypatch.setattr(token_manager, "_now", fake)
    return fake


class FakeScheduledCall:
    def __init__(self, delay, callback):
        self.delay = delay
        self.callback = callback
        self.cancelled = False

    def cancel(self):
        self.cancelled = True


class FakeScheduler:
    """Captures scheduled callbacks so a test can fire them deterministically."""

    def __init__(self):
        self.calls = []
        self.fail = False

    def __call__(self, delay, callback):
        if self.fail:
            raise RuntimeError("can't start new thread")
        call = FakeScheduledCall(delay, callback)
        self.calls.append(call)
        return call

    @property
    def pending(self):
        return [call for call in self.calls if not call.cancelled]

    def fire_pending(self):
        """Run every live callback once, on this thread, as the timer would."""
        for call in self.pending:
            call.cancel()
            call.callback()


@pytest.fixture(autouse=True)
def scheduler(monkeypatch):
    fake = FakeScheduler()
    monkeypatch.setattr(token_manager, "_schedule", fake)
    return fake


class FakeSleep:
    """Records retry delays instead of sleeping.

    The real ``_wait`` honours close() through the stop event; that contract is
    covered separately by ``test_real_wait_returns_early_when_stopped``.
    """

    def __init__(self):
        self.delays = []
        self.on_sleep = None

    def __call__(self, stop, seconds):
        self.delays.append(round(seconds, 6))
        if self.on_sleep is not None:
            self.on_sleep()


@pytest.fixture(autouse=True)
def sleep(monkeypatch):
    fake = FakeSleep()
    monkeypatch.setattr(token_manager, "_wait", fake)
    # Midpoint jitter (factor 1.0) so recorded delays equal the nominal backoff.
    monkeypatch.setattr(token_manager, "_random", lambda: 0.5)
    return fake


def _failing_then_ok(session, failures, error=None):
    """Make credential resolution raise ``failures`` times, then succeed."""
    credentials = session.get_credentials.return_value
    calls = {"n": 0}

    def side_effect():
        calls["n"] += 1
        if calls["n"] <= failures:
            raise error or RuntimeError(f"transient failure {calls['n']}")
        return credentials

    session.get_credentials.side_effect = side_effect


def _manager(session, **kwargs):
    kwargs.setdefault("serverless_cache_name", CACHE)
    kwargs.setdefault("user_id", USER)
    kwargs.setdefault("region", REGION)
    return ElastiCacheIAMAuthTokenManager(session=session, **kwargs)


def _run_concurrently(count, target):
    """Start ``count`` threads that all enter ``target`` at once; return their results."""
    results = []
    results_lock = threading.Lock()
    ready = threading.Barrier(count)

    def worker():
        ready.wait(timeout=5)
        try:
            outcome = target()
        except Exception as error:  # noqa: BLE001  # collected for the assertions
            outcome = error
        with results_lock:
            results.append(outcome)

    threads = [threading.Thread(target=worker) for _ in range(count)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=5)
    assert len(results) == count
    return results


# --- errors and exports ------------------------------------------------------------


def test_token_refresh_error_is_not_an_input_error():
    assert issubclass(TokenRefreshError, Exception)
    assert not issubclass(TokenRefreshError, ToolkitInputError)


def test_package_root_exports_the_manager_api():
    assert package.ElastiCacheIAMAuthTokenManager is ElastiCacheIAMAuthTokenManager
    assert package.TokenRefreshError is TokenRefreshError
    assert package.DEFAULT_REFRESH_AFTER_SECONDS == DEFAULT_REFRESH_AFTER_SECONDS
    assert "ElastiCacheIAMAuthTokenManager" in package.__all__
    assert "TokenRefreshError" in package.__all__


# --- caching -----------------------------------------------------------------------


def test_signs_lazily_and_caches_the_token(clock, mock_session):
    manager = _manager(mock_session)
    assert mock_session.get_credentials.call_count == 0

    first = manager.get_token()
    second = manager.get_token()

    assert first == second
    assert f"{CACHE}/?" in first
    assert mock_session.get_credentials.call_count == 1


def test_serves_the_cached_token_until_the_serve_margin(clock, mock_session):
    # Stop 20s before the real 900s expiry to absorb clock skew and the AUTH round trip.
    manager = _manager(mock_session)
    manager.get_token()

    clock.advance(EFFECTIVE_LIFETIME - 1)
    manager.get_token()
    assert mock_session.get_credentials.call_count == 1

    clock.advance(1)
    manager.get_token()
    assert mock_session.get_credentials.call_count == 2


def test_uses_the_wall_clock_for_expiry():
    # The server judges the token by X-Amz-Date against its wall clock; a monotonic
    # clock would stand still through a host suspend and overstate validity.
    assert token_manager._now is time.time


def test_get_credentials_returns_the_normalized_user_id_and_cached_token(
    clock, mock_session
):
    manager = _manager(mock_session, user_id="MixedCaseUser")

    user_id, token = manager.get_credentials()

    assert user_id == "mixedcaseuser"
    assert token == manager.get_token()
    assert manager.user_id == "mixedcaseuser"


def test_cached_token_repr_does_not_contain_the_token(clock, mock_session):
    manager = _manager(mock_session)
    token = manager.get_token()

    assert "X-Amz-Signature" not in repr(manager._cached)
    assert token not in repr(manager._cached)


def test_first_signing_failure_is_the_underlying_error(clock, sleep):
    manager = _manager(fake_session(credentials=None))

    with pytest.raises(ConfigurationError):
        manager.get_token()
    assert len(sleep.delays) == MAX_ATTEMPTS - 1


def test_validates_parameters_at_construction(clock, mock_session):
    with pytest.raises(InvalidParameterError):
        _manager(mock_session, serverless_cache_name="bad_name!")
    with pytest.raises(ConfigurationError):
        _manager(fake_session(region=None), region=None)


# --- single flight -----------------------------------------------------------------


def test_concurrent_callers_share_one_signing(clock):
    session = fake_session()
    credentials = session.get_credentials.return_value

    def slow_get_credentials():
        # Hold the flight open long enough for every worker to join it.
        time.sleep(0.05)
        return credentials

    session.get_credentials.side_effect = slow_get_credentials
    manager = _manager(session)

    tokens = _run_concurrently(5, manager.get_token)

    assert all(isinstance(token, str) for token in tokens)
    assert session.get_credentials.call_count == 1


def test_concurrent_callers_share_one_failure(clock, sleep):
    # A failed cycle is reported to every waiter, rather than each waiter running its
    # own eight attempts in turn.
    session = fake_session()
    _failing_then_ok(session, failures=1_000)
    sleep.on_sleep = lambda: time.sleep(0.005)
    manager = _manager(session)

    outcomes = _run_concurrently(4, manager.get_token)

    assert all(isinstance(outcome, RuntimeError) for outcome in outcomes)
    assert len({id(outcome) for outcome in outcomes}) == 1
    assert session.get_credentials.call_count == MAX_ATTEMPTS


# --- refresh_after -----------------------------------------------------------------


def test_default_refresh_after_is_five_minutes():
    assert DEFAULT_REFRESH_AFTER_SECONDS == 300


@pytest.mark.parametrize(
    "refresh_after",
    [
        0,
        -1,
        EFFECTIVE_LIFETIME,
        EFFECTIVE_LIFETIME + 1,
        TOKEN_TTL,
        "300",
        True,
        float("nan"),
    ],
)
def test_rejects_invalid_refresh_after(clock, mock_session, refresh_after):
    with pytest.raises(InvalidParameterError) as excinfo:
        _manager(mock_session, refresh_after=refresh_after)
    assert "refresh_after" in str(excinfo.value)


@pytest.mark.parametrize("refresh_after", [0.001, 1, EFFECTIVE_LIFETIME - 0.001])
def test_accepts_refresh_after_within_the_token_lifetime(
    clock, scheduler, mock_session, refresh_after
):
    manager = _manager(mock_session, refresh_after=refresh_after)
    manager.get_token()

    assert [call.delay for call in scheduler.pending] == [refresh_after]


# --- background refresh ------------------------------------------------------------


def test_schedules_a_background_refresh_after_the_first_token(
    clock, scheduler, mock_session
):
    manager = _manager(mock_session)
    assert scheduler.pending == []

    manager.get_token()

    assert [call.delay for call in scheduler.pending] == [DEFAULT_REFRESH_AFTER_SECONDS]


def test_background_refresh_installs_rotated_credentials(clock, scheduler):
    session = fake_session()
    manager = _manager(session)
    first = manager.get_token()
    session.get_credentials.return_value.get_frozen_credentials.return_value = (
        ROTATED_CREDENTIALS
    )

    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS)
    scheduler.fire_pending()

    second = manager.get_token()
    assert second != first
    assert "AKIAI44QH8DHBEXAMPLE" in second
    assert session.get_credentials.call_count == 2
    # The new token gets its own refresh timer.
    assert [call.delay for call in scheduler.pending] == [DEFAULT_REFRESH_AFTER_SECONDS]


def test_get_token_past_the_refresh_point_does_not_block(clock, scheduler):
    # Timer never fired (e.g. the process was suspended): the caller still gets the
    # current token immediately, and a refresh is kicked off in the background.
    session = fake_session()
    manager = _manager(session)
    first = manager.get_token()

    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS + 1)
    assert manager.get_token() == first
    assert session.get_credentials.call_count == 1
    assert [call.delay for call in scheduler.pending] == [0]

    scheduler.fire_pending()
    assert session.get_credentials.call_count == 2


def test_only_one_background_refresh_is_started(clock, scheduler, mock_session):
    manager = _manager(mock_session)
    manager.get_token()
    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS + 1)

    manager.get_token()
    manager.get_token()

    assert len(scheduler.pending) == 1


def test_missed_timer_path_works_again_after_a_refresh_completes(
    clock, scheduler, mock_session
):
    manager = _manager(mock_session)
    manager.get_token()
    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS + 1)
    manager.get_token()
    scheduler.fire_pending()  # first missed-timer refresh completes
    assert mock_session.get_credentials.call_count == 2

    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS + 1)
    manager.get_token()
    scheduler.fire_pending()

    assert mock_session.get_credentials.call_count == 3


def test_stale_background_refresh_skips_signing_when_a_newer_token_exists(
    clock, scheduler, mock_session
):
    manager = _manager(mock_session)
    manager.get_token()
    (stale_timer,) = scheduler.pending
    clock.advance(EFFECTIVE_LIFETIME)  # expired: the next call signs synchronously ...
    manager.get_token()
    assert mock_session.get_credentials.call_count == 2

    stale_timer.callback()  # ... so the old timer, firing late, has nothing to do

    assert mock_session.get_credentials.call_count == 2


def test_scheduling_failure_does_not_lose_the_token(clock, scheduler, mock_session):
    scheduler.fail = True
    manager = _manager(mock_session)

    token = manager.get_token()  # signed, installed, timer could not be started

    assert f"{CACHE}/?" in token
    assert manager.get_token() == token
    # The due time is left in the past so the next call re-attempts scheduling.
    scheduler.fail = False
    manager.get_token()
    assert [call.delay for call in scheduler.pending] == [0]


def test_default_scheduler_uses_a_daemon_timer():
    timer = REAL_SCHEDULE(60, lambda: None)
    try:
        assert isinstance(timer, threading.Timer)
        assert timer.daemon
    finally:
        timer.cancel()


# --- retry and failure semantics ---------------------------------------------------


def test_retries_transient_failures_with_exponential_backoff(clock, sleep):
    session = fake_session()
    _failing_then_ok(session, failures=3)
    manager = _manager(session)

    token = manager.get_token()

    assert f"{CACHE}/?" in token
    assert session.get_credentials.call_count == 4
    assert sleep.delays == [0.1, 0.2, 0.4]


def test_backoff_is_capped(clock, sleep):
    session = fake_session()
    _failing_then_ok(session, failures=MAX_ATTEMPTS - 1)
    manager = _manager(session)

    manager.get_token()

    assert session.get_credentials.call_count == MAX_ATTEMPTS
    assert sleep.delays == [0.1, 0.2, 0.4, 0.8, 1.6, 3.2, MAX_DELAY]


# random() never returns 1.0, so 1.0 here just pins the formula's upper bound (+20%).
@pytest.mark.parametrize(("draw", "expected"), [(0.0, 0.08), (1.0, 0.12)])
def test_backoff_is_jittered(clock, sleep, monkeypatch, draw, expected):
    monkeypatch.setattr(token_manager, "_random", lambda: draw)
    session = fake_session()
    _failing_then_ok(session, failures=1)
    manager = _manager(session)

    manager.get_token()

    assert sleep.delays == [expected]


def test_first_token_failure_after_all_attempts_is_the_underlying_error(clock, sleep):
    session = fake_session()
    _failing_then_ok(session, failures=MAX_ATTEMPTS + 1)
    manager = _manager(session)

    with pytest.raises(RuntimeError, match="transient failure 8"):
        manager.get_token()
    assert session.get_credentials.call_count == MAX_ATTEMPTS
    assert len(sleep.delays) == MAX_ATTEMPTS - 1


def test_configuration_errors_are_retried_too(clock, sleep):
    # On an instance role a transient IMDS failure surfaces as "no credentials"
    # (botocore returns None), so ConfigurationError gets the same retries.
    session = fake_session()
    calls = {"n": 0}
    credentials = session.get_credentials.return_value

    def flaky_chain():
        calls["n"] += 1
        return None if calls["n"] <= 2 else credentials

    session.get_credentials.side_effect = flaky_chain
    manager = _manager(session)

    token = manager.get_token()

    assert f"{CACHE}/?" in token
    assert sleep.delays == [0.1, 0.2]


def test_serves_the_valid_token_while_refresh_keeps_failing(clock, scheduler, sleep):
    session = fake_session()
    manager = _manager(session)
    first = manager.get_token()
    _failing_then_ok(session, failures=MAX_ATTEMPTS + 1)

    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS)
    scheduler.fire_pending()

    assert manager.get_token() == first
    assert len(sleep.delays) == MAX_ATTEMPTS - 1
    # Another cycle is scheduled after the maximum backoff, not at refresh_after.
    assert [call.delay for call in scheduler.pending] == [MAX_DELAY]


def test_configuration_error_in_the_background_still_reschedules(
    clock, scheduler, sleep, mock_session
):
    # The cycle must end normally: keep the valid token, reschedule, and never let
    # get_token() spawn a refresh per call.
    manager = _manager(mock_session)
    first = manager.get_token()
    mock_session.get_credentials.return_value = None  # credential chain went empty

    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS)
    scheduler.fire_pending()

    assert len(sleep.delays) == MAX_ATTEMPTS - 1
    assert [call.delay for call in scheduler.pending] == [MAX_DELAY]
    for _ in range(3):
        assert manager.get_token() == first
    assert mock_session.get_credentials.call_count == 1 + MAX_ATTEMPTS
    assert [call.delay for call in scheduler.pending] == [MAX_DELAY]


def test_get_token_does_not_restart_a_refresh_during_the_backoff(clock, scheduler, sleep):
    session = fake_session()
    manager = _manager(session)
    manager.get_token()
    _failing_then_ok(session, failures=1_000)
    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS)
    scheduler.fire_pending()
    attempts = session.get_credentials.call_count

    clock.advance(MAX_DELAY - 1)
    manager.get_token()
    assert session.get_credentials.call_count == attempts
    assert [call.delay for call in scheduler.pending] == [MAX_DELAY]

    clock.advance(1)
    manager.get_token()  # backoff over and timer not fired: treated as missed
    assert [call.delay for call in scheduler.pending] == [0]


def test_raises_token_refresh_error_once_the_cached_token_expired(
    clock, scheduler, sleep
):
    session = fake_session()
    manager = _manager(session)
    manager.get_token()
    _failing_then_ok(session, failures=1_000)
    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS)
    scheduler.fire_pending()  # background cycle fails; token still valid

    clock.advance(EFFECTIVE_LIFETIME)
    with pytest.raises(TokenRefreshError) as excinfo:
        manager.get_token()

    assert "before the previous token expired" in str(excinfo.value)
    assert isinstance(excinfo.value.__cause__, RuntimeError)
    assert "transient failure" not in str(excinfo.value)


def test_expired_token_with_a_configuration_error_is_a_refresh_error(
    clock, scheduler, sleep, mock_session
):
    manager = _manager(mock_session)
    manager.get_token()
    mock_session.get_credentials.return_value = None
    clock.advance(EFFECTIVE_LIFETIME)

    with pytest.raises(TokenRefreshError) as excinfo:
        manager.get_token()

    assert isinstance(excinfo.value.__cause__, ConfigurationError)


# --- on_token_changed --------------------------------------------------------------


def test_on_token_changed_receives_each_installed_token(clock, scheduler):
    session = fake_session()
    seen = []
    manager = _manager(session, on_token_changed=seen.append)

    first = manager.get_token()
    assert seen == [first]

    session.get_credentials.return_value.get_frozen_credentials.return_value = (
        ROTATED_CREDENTIALS
    )
    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS)
    scheduler.fire_pending()

    assert seen == [first, manager.get_token()]
    assert seen[0] != seen[1]


def test_on_token_changed_failures_are_ignored(clock, mock_session):
    def explode(_token):
        raise RuntimeError("consumer bug")

    manager = _manager(mock_session, on_token_changed=explode)

    token = manager.get_token()

    assert manager.get_token() == token
    assert mock_session.get_credentials.call_count == 1


def test_on_token_changed_runs_outside_the_lock(clock, mock_session):
    # A client may re-authenticate and ask for the credentials again from inside the
    # callback, and other threads must not be blocked behind it.
    seen = []
    manager = _manager(
        mock_session,
        on_token_changed=lambda _t: seen.append(
            (manager.get_credentials(), manager._lock.locked())
        ),
    )

    token = manager.get_token()

    assert seen == [((USER, token), False)]


def test_on_token_changed_failure_is_logged_without_the_token(
    clock, mock_session, caplog
):
    def explode(_token):
        raise RuntimeError("consumer bug")

    manager = _manager(mock_session, on_token_changed=explode)

    with caplog.at_level("WARNING", logger="developer_toolkit_elasticache"):
        token = manager.get_token()

    assert [r.levelname for r in caplog.records] == ["WARNING"]
    assert "on_token_changed" in caplog.text
    assert "consumer bug" in caplog.text
    assert token not in caplog.text


def test_failed_background_refresh_is_logged_without_the_token(
    clock, scheduler, sleep, caplog
):
    session = fake_session()
    manager = _manager(session)
    token = manager.get_token()
    _failing_then_ok(session, failures=1_000)
    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS)

    with caplog.at_level("WARNING", logger="developer_toolkit_elasticache"):
        scheduler.fire_pending()

    assert [r.levelname for r in caplog.records] == ["WARNING"]
    assert "Could not refresh" in caplog.text
    assert "RuntimeError" in caplog.text
    assert "retrying in 5s" in caplog.text
    assert token not in caplog.text


def test_on_token_changed_is_not_called_for_a_failed_refresh(clock, scheduler, sleep):
    session = fake_session()
    seen = []
    manager = _manager(session, on_token_changed=seen.append)
    manager.get_token()
    _failing_then_ok(session, failures=1_000)

    clock.advance(DEFAULT_REFRESH_AFTER_SECONDS)
    scheduler.fire_pending()

    assert len(seen) == 1


# --- close -------------------------------------------------------------------------


def test_close_rejects_later_requests(clock, mock_session):
    manager = _manager(mock_session)
    manager.get_token()

    manager.close()

    with pytest.raises(TokenRefreshError):
        manager.get_token()
    with pytest.raises(TokenRefreshError):
        manager.get_credentials()


def test_close_is_idempotent(clock, mock_session):
    manager = _manager(mock_session)
    manager.get_token()

    manager.close()
    manager.close()

    with pytest.raises(TokenRefreshError):
        manager.get_token()


def test_context_manager_closes(clock, mock_session):
    with _manager(mock_session) as manager:
        manager.get_token()

    with pytest.raises(TokenRefreshError):
        manager.get_token()


def test_close_cancels_the_pending_refresh(clock, scheduler, mock_session):
    manager = _manager(mock_session)
    manager.get_token()
    (pending,) = scheduler.pending

    manager.close()

    assert pending.cancelled
    pending.callback()  # a timer that already fired must be a no-op after close
    assert mock_session.get_credentials.call_count == 1


def test_close_during_a_retry_stops_the_cycle(clock, sleep):
    session = fake_session()
    _failing_then_ok(session, failures=1_000)
    manager = _manager(session)
    sleep.on_sleep = manager.close

    with pytest.raises(TokenRefreshError) as excinfo:
        manager.get_token()

    assert "closed" in str(excinfo.value)
    assert isinstance(excinfo.value.__cause__, RuntimeError)
    assert session.get_credentials.call_count == 1


def test_close_during_signing_discards_the_token(clock):
    session = fake_session()
    seen = []
    manager = _manager(session, on_token_changed=seen.append)
    credentials = session.get_credentials.return_value

    def close_mid_signing():
        manager.close()
        return credentials

    session.get_credentials.side_effect = close_mid_signing

    with pytest.raises(TokenRefreshError):
        manager.get_token()
    assert seen == []
    assert manager._cached is None


def test_real_wait_returns_early_when_stopped():
    stop = threading.Event()
    stop.set()
    started = time.monotonic()

    REAL_WAIT(stop, 60)

    assert time.monotonic() - started < 1

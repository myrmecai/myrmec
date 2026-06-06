"""Tests for the rate-limit retry helper."""

from __future__ import annotations

import asyncio
from email.utils import format_datetime
from datetime import datetime, timedelta, timezone

import pytest

from myrmec.agent.retry import (
    RateLimitRetryConfig,
    extract_retry_after,
    is_rate_limit_error,
    retry_on_rate_limit,
)


# ---------------------------------------------------------------------------
# Fakes
# ---------------------------------------------------------------------------


class _FakeHeaders(dict):
    """Dict-with-case-insensitive-get good enough for tests."""

    def get(self, key, default=None):  # type: ignore[override]
        return super().get(key, super().get(key.lower(), default))


class _FakeResponse:
    def __init__(self, status_code: int, headers: dict | None = None) -> None:
        self.status_code = status_code
        self.headers = _FakeHeaders(headers or {})


class FakeRateLimitError(Exception):
    """Mimics ``openai.RateLimitError`` / ``anthropic.RateLimitError``."""

    def __init__(self, message: str = "rate limit exceeded", *, response=None, status_code=None):
        super().__init__(message)
        if response is not None:
            self.response = response
        if status_code is not None:
            self.status_code = status_code


class FakeHTTPStatusError(Exception):
    """Mimics ``httpx.HTTPStatusError``."""

    def __init__(self, response: _FakeResponse) -> None:
        super().__init__(f"HTTP {response.status_code}")
        self.response = response


# ---------------------------------------------------------------------------
# is_rate_limit_error
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "exc,expected",
    [
        (FakeRateLimitError("rate limit"), True),
        (FakeRateLimitError("nope", status_code=429), True),
        (FakeHTTPStatusError(_FakeResponse(429)), True),
        (FakeHTTPStatusError(_FakeResponse(500)), False),
        (Exception("HTTP/1.1 429 Too Many Requests"), True),
        (Exception("quota exceeded"), True),
        (Exception("something else"), False),
        (ValueError("bad input"), False),
        (TimeoutError("slow"), False),
    ],
)
def test_is_rate_limit_error(exc, expected):
    assert is_rate_limit_error(exc) is expected


# ---------------------------------------------------------------------------
# extract_retry_after
# ---------------------------------------------------------------------------


def test_extract_retry_after_integer_seconds():
    exc = FakeHTTPStatusError(_FakeResponse(429, {"Retry-After": "12"}))
    assert extract_retry_after(exc) == pytest.approx(12.0)


def test_extract_retry_after_float_seconds():
    exc = FakeHTTPStatusError(_FakeResponse(429, {"retry-after": "2.5"}))
    assert extract_retry_after(exc) == pytest.approx(2.5)


def test_extract_retry_after_http_date():
    future = datetime.now(timezone.utc) + timedelta(seconds=30)
    header = format_datetime(future, usegmt=True)
    exc = FakeHTTPStatusError(_FakeResponse(429, {"Retry-After": header}))
    delay = extract_retry_after(exc)
    assert delay is not None
    assert 25.0 <= delay <= 35.0


def test_extract_retry_after_missing_returns_none():
    assert extract_retry_after(FakeRateLimitError("rate limit")) is None
    assert extract_retry_after(FakeHTTPStatusError(_FakeResponse(429))) is None
    assert extract_retry_after(FakeHTTPStatusError(_FakeResponse(429, {"Retry-After": ""}))) is None


def test_extract_retry_after_unparseable_returns_none():
    exc = FakeHTTPStatusError(_FakeResponse(429, {"Retry-After": "not-a-date"}))
    assert extract_retry_after(exc) is None


# ---------------------------------------------------------------------------
# retry_on_rate_limit
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _no_sleep(monkeypatch):
    """Don't actually sleep during retry tests."""
    async def _fast_sleep(_):
        return None

    monkeypatch.setattr(asyncio, "sleep", _fast_sleep)


@pytest.mark.asyncio
async def test_returns_on_first_success():
    calls = 0

    async def fn():
        nonlocal calls
        calls += 1
        return "ok"

    result = await retry_on_rate_limit(fn, config=RateLimitRetryConfig(max_attempts=3))
    assert result == "ok"
    assert calls == 1


@pytest.mark.asyncio
async def test_retries_until_success():
    attempts = 0
    retries: list[tuple[int, float]] = []

    async def fn():
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            raise FakeRateLimitError("rate limit")
        return "done"

    async def on_retry(attempt, delay, exc):
        retries.append((attempt, delay))

    result = await retry_on_rate_limit(
        fn,
        config=RateLimitRetryConfig(max_attempts=5, base_delay=0.1, jitter=0),
        on_retry=on_retry,
    )
    assert result == "done"
    assert attempts == 3
    assert [r[0] for r in retries] == [1, 2]


@pytest.mark.asyncio
async def test_reraises_after_exhausting_budget():
    async def fn():
        raise FakeRateLimitError("still limited")

    with pytest.raises(FakeRateLimitError):
        await retry_on_rate_limit(
            fn,
            config=RateLimitRetryConfig(max_attempts=3, base_delay=0.01, jitter=0),
        )


@pytest.mark.asyncio
async def test_non_rate_limit_propagates_immediately():
    calls = 0

    async def fn():
        nonlocal calls
        calls += 1
        raise ValueError("bad input")

    with pytest.raises(ValueError):
        await retry_on_rate_limit(fn, config=RateLimitRetryConfig(max_attempts=5))
    assert calls == 1


@pytest.mark.asyncio
async def test_honours_retry_after_header():
    delays: list[float] = []
    attempts = 0

    async def fn():
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise FakeHTTPStatusError(_FakeResponse(429, {"Retry-After": "7"}))
        return "ok"

    async def on_retry(attempt, delay, exc):
        delays.append(delay)

    await retry_on_rate_limit(
        fn,
        config=RateLimitRetryConfig(max_attempts=3, base_delay=99.0, max_delay=99.0, jitter=0),
        on_retry=on_retry,
    )
    assert delays == [pytest.approx(7.0)]


@pytest.mark.asyncio
async def test_max_total_wait_caps_retries():
    attempts = 0

    async def fn():
        nonlocal attempts
        attempts += 1
        raise FakeRateLimitError("limit")

    with pytest.raises(FakeRateLimitError):
        await retry_on_rate_limit(
            fn,
            config=RateLimitRetryConfig(
                max_attempts=10,
                base_delay=5.0,
                max_delay=5.0,
                jitter=0,
                max_total_wait=7.0,
            ),
        )
    # First retry sleeps 5s (total=5), second is capped to remaining=2s
    # (total=7s, budget exhausted), the third failure bails out — so 3 attempts
    # total before re-raising.
    assert attempts == 3

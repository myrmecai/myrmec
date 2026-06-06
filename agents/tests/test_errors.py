"""Tests for errors.classify_exception."""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

from myrmec.agent.errors import (
    ClassifiedError,
    ErrorCode,
    ToolExecutionError,
    classify_exception,
    classify_exception_with_hint,
)


@pytest.mark.parametrize(
    "exc, expected",
    [
        (asyncio.TimeoutError(), ErrorCode.TIMEOUT),
        (asyncio.CancelledError(), ErrorCode.CANCELLED),
        (ToolExecutionError("search", "boom"), ErrorCode.TOOL_FAILED),
        (ValueError("bad input"), ErrorCode.INPUT_INVALID),
        (TypeError("nope"), ErrorCode.INPUT_INVALID),
        (KeyError("missing"), ErrorCode.INPUT_INVALID),
        (RuntimeError("Rate limit exceeded for gpt-4"), ErrorCode.MODEL_RATE_LIMITED),
        (RuntimeError("429 Too Many Requests"), ErrorCode.MODEL_RATE_LIMITED),
        (RuntimeError("Invalid API key provided"), ErrorCode.MODEL_AUTH),
        (RuntimeError("401 Unauthorized"), ErrorCode.MODEL_AUTH),
        (RuntimeError("OpenAI server returned 500"), ErrorCode.MODEL_ERROR),
        (RuntimeError("ConnectError: dns failure"), ErrorCode.NETWORK_ERROR),
        (RuntimeError("read timeout"), ErrorCode.TIMEOUT),
        (RuntimeError("totally unknown failure mode"), ErrorCode.INTERNAL),
    ],
)
def test_classify_exception(exc: BaseException, expected: ErrorCode) -> None:
    assert classify_exception(exc) is expected


def test_error_code_string_value() -> None:
    """ErrorCode values are stable strings (engine + UI rely on this)."""
    assert ErrorCode.TIMEOUT.value == "TIMEOUT"
    assert ErrorCode.MODEL_RATE_LIMITED.value == "MODEL_RATE_LIMITED"
    # str(Enum) shouldn't accidentally include the class prefix in payloads.
    assert ErrorCode.TIMEOUT.value == str(ErrorCode.TIMEOUT.value)


def _exc_with_retry_after(value: object) -> BaseException:
    """Build an exception that carries a Retry-After header in the
    shape ``extract_retry_after`` expects (``exc.response.headers``)."""
    exc = RuntimeError("429 Too Many Requests")
    exc.response = SimpleNamespace(  # type: ignore[attr-defined]
        status_code=429,
        headers={"Retry-After": str(value)},
    )
    return exc


def test_classify_exception_with_hint_extracts_seconds() -> None:
    result = classify_exception_with_hint(_exc_with_retry_after(42))
    assert result == ClassifiedError(
        code=ErrorCode.MODEL_RATE_LIMITED, retry_after_seconds=42
    )


def test_classify_exception_with_hint_rounds_fractional_up() -> None:
    # 3.2s of provider backoff must round to 4s, never 3s, otherwise
    # the engine would re-dispatch the task while the window is still
    # open and immediately hit another 429.
    result = classify_exception_with_hint(_exc_with_retry_after("3.2"))
    assert result.code is ErrorCode.MODEL_RATE_LIMITED
    assert result.retry_after_seconds == 4


def test_classify_exception_with_hint_no_header() -> None:
    exc = RuntimeError("429 Too Many Requests")
    result = classify_exception_with_hint(exc)
    assert result.code is ErrorCode.MODEL_RATE_LIMITED
    assert result.retry_after_seconds is None


def test_classify_exception_with_hint_non_rate_limit_returns_none() -> None:
    # A non-rate-limit error must NOT carry a retry hint even if the
    # underlying exception somehow has a Retry-After header.
    exc = RuntimeError("Invalid API key provided")
    exc.response = SimpleNamespace(  # type: ignore[attr-defined]
        status_code=401, headers={"Retry-After": "5"}
    )
    result = classify_exception_with_hint(exc)
    assert result.code is ErrorCode.MODEL_AUTH
    assert result.retry_after_seconds is None

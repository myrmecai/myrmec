"""Tests for errors.classify_exception."""

from __future__ import annotations

import asyncio

import pytest

from myrmec.agent.errors import ErrorCode, ToolExecutionError, classify_exception


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

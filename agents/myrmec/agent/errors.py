"""
Standardised error taxonomy for task failures.

Agents emit a stable ``errorCode`` on every ``task.failed`` message so that the
engine and UI can colour-code, group, and reason about failure modes without
having to parse exception class names from third-party libraries.
"""

from __future__ import annotations

import asyncio
from enum import Enum


class ErrorCode(str, Enum):
    """Canonical task-failure codes shared with the engine."""

    TIMEOUT = "TIMEOUT"
    """The task or one of its calls exceeded its time budget."""

    CANCELLED = "CANCELLED"
    """The task was cancelled (by user, engine, or shutdown)."""

    TOOL_FAILED = "TOOL_FAILED"
    """A tool invocation raised an unrecoverable error."""

    MODEL_RATE_LIMITED = "MODEL_RATE_LIMITED"
    """LLM provider returned a rate-limit / quota error."""

    MODEL_AUTH = "MODEL_AUTH"
    """LLM provider rejected the credentials."""

    MODEL_ERROR = "MODEL_ERROR"
    """Any other LLM provider error (bad request, server error, etc.)."""

    NETWORK_ERROR = "NETWORK_ERROR"
    """Transport-layer failure (DNS, TCP, TLS, broken connection, ...)."""

    INPUT_INVALID = "INPUT_INVALID"
    """The task input or configuration failed validation."""

    INTERNAL = "INTERNAL"
    """Unclassified internal error."""


# ---------------------------------------------------------------------------
# Heuristics
# ---------------------------------------------------------------------------

# Substring → ErrorCode. Lowercased compare against exception class FQN +
# message. First match wins, so order matters: most specific patterns first.
_PATTERNS: tuple[tuple[str, ErrorCode], ...] = (
    ("rate limit", ErrorCode.MODEL_RATE_LIMITED),
    ("ratelimit", ErrorCode.MODEL_RATE_LIMITED),
    ("quota", ErrorCode.MODEL_RATE_LIMITED),
    ("429", ErrorCode.MODEL_RATE_LIMITED),
    ("authentication", ErrorCode.MODEL_AUTH),
    ("unauthorized", ErrorCode.MODEL_AUTH),
    ("invalid api key", ErrorCode.MODEL_AUTH),
    ("permissiondenied", ErrorCode.MODEL_AUTH),
    ("401", ErrorCode.MODEL_AUTH),
    ("403", ErrorCode.MODEL_AUTH),
    ("openai", ErrorCode.MODEL_ERROR),
    ("anthropic", ErrorCode.MODEL_ERROR),
    ("apierror", ErrorCode.MODEL_ERROR),
    ("badrequest", ErrorCode.MODEL_ERROR),
    ("httpstatuserror", ErrorCode.MODEL_ERROR),
    ("connecterror", ErrorCode.NETWORK_ERROR),
    ("connectionerror", ErrorCode.NETWORK_ERROR),
    ("readtimeout", ErrorCode.TIMEOUT),
    ("writetimeout", ErrorCode.TIMEOUT),
    ("connecttimeout", ErrorCode.NETWORK_ERROR),
    ("timeout", ErrorCode.TIMEOUT),
    ("toolexecutionerror", ErrorCode.TOOL_FAILED),
    ("tool_failed", ErrorCode.TOOL_FAILED),
    ("validationerror", ErrorCode.INPUT_INVALID),
    ("valueerror", ErrorCode.INPUT_INVALID),
    ("typeerror", ErrorCode.INPUT_INVALID),
    ("keyerror", ErrorCode.INPUT_INVALID),
)


class ToolExecutionError(Exception):
    """Raised when a tool invocation fails inside a tool tracker.

    The :class:`classify_exception` helper maps this to
    :attr:`ErrorCode.TOOL_FAILED`.
    """

    def __init__(self, tool_name: str, message: str) -> None:
        super().__init__(f"Tool '{tool_name}' failed: {message}")
        self.tool_name = tool_name


def classify_exception(exc: BaseException) -> ErrorCode:
    """Map an arbitrary exception to a canonical :class:`ErrorCode`.

    The mapping is intentionally permissive: any unrecognised exception
    becomes :attr:`ErrorCode.INTERNAL` rather than failing the classification.
    """
    if isinstance(exc, asyncio.TimeoutError):
        return ErrorCode.TIMEOUT
    if isinstance(exc, asyncio.CancelledError):
        return ErrorCode.CANCELLED
    if isinstance(exc, ToolExecutionError):
        return ErrorCode.TOOL_FAILED
    if isinstance(exc, (ValueError, TypeError, KeyError)):
        return ErrorCode.INPUT_INVALID

    haystack = f"{type(exc).__module__}.{type(exc).__name__} {exc}".lower()
    for needle, code in _PATTERNS:
        if needle in haystack:
            return code
    return ErrorCode.INTERNAL


__all__ = ["ErrorCode", "ToolExecutionError", "classify_exception"]

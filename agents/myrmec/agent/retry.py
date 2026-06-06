"""
Rate-limit retry helper for LLM provider calls.

A single ``429 Too Many Requests`` from a provider should not fail a whole
task — most rate limits clear within seconds. This module provides a small
backoff helper that:

* detects rate-limit errors across the common provider SDKs (OpenAI,
  Anthropic, raw ``httpx`` 429s, and anything whose message mentions
  ``429`` / ``rate limit`` / ``quota``);
* honours the ``Retry-After`` header when the provider supplies one;
* otherwise applies exponential backoff with jitter;
* caps the total time spent waiting so a long outage still fails fast.

After the retry budget is exhausted the original exception is re-raised, and
the existing :mod:`myrmec.agent.errors` taxonomy maps it to
``MODEL_RATE_LIMITED`` on the resulting ``task.failed`` message.
"""

from __future__ import annotations

import asyncio
import logging
import random
import time
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from typing import Awaitable, Callable, TypeVar

logger = logging.getLogger(__name__)

T = TypeVar("T")

OnRetry = Callable[[int, float, BaseException], Awaitable[None]]


@dataclass(frozen=True)
class RateLimitRetryConfig:
    """Retry budget for a single model invocation."""

    max_attempts: int = 4
    """Total attempts including the first call (so ``max_attempts=4`` means
    up to 3 retries)."""

    base_delay: float = 1.0
    """Initial backoff in seconds before the first retry."""

    max_delay: float = 30.0
    """Cap for a single backoff sleep."""

    jitter: float = 0.25
    """Fractional jitter (``0.25`` = ±25%) applied to computed backoff."""

    max_total_wait: float = 60.0
    """Hard cap on cumulative sleep time across all retries."""


_RATE_LIMIT_SUBSTRINGS: tuple[str, ...] = (
    "rate limit",
    "ratelimit",
    "rate_limit",
    "quota",
    "429",
    "too many requests",
)


def is_rate_limit_error(exc: BaseException) -> bool:
    """Return True iff ``exc`` looks like a provider rate-limit error.

    The check is intentionally broad: provider SDKs are not consistent about
    exception class names, so we look at (in order):

    1. an explicit ``status_code`` attribute equal to 429;
    2. a ``response.status_code`` attribute equal to 429 (``httpx`` shape);
    3. the exception class name containing ``RateLimit``;
    4. a recognised substring in the stringified exception or its module.
    """
    status = getattr(exc, "status_code", None)
    if status == 429:
        return True

    response = getattr(exc, "response", None)
    if response is not None and getattr(response, "status_code", None) == 429:
        return True

    cls = type(exc).__name__.lower()
    if "ratelimit" in cls:
        return True

    haystack = f"{type(exc).__module__}.{type(exc).__name__} {exc}".lower()
    return any(s in haystack for s in _RATE_LIMIT_SUBSTRINGS)


def extract_retry_after(exc: BaseException) -> float | None:
    """Read the ``Retry-After`` header from a provider exception.

    Supports both the integer-seconds form (``Retry-After: 30``) and the
    HTTP-date form (``Retry-After: Wed, 21 Oct 2026 07:28:00 GMT``).
    Returns ``None`` if the header is absent or unparseable.
    """
    response = getattr(exc, "response", None)
    if response is None:
        return None

    headers = getattr(response, "headers", None)
    if not headers:
        return None

    try:
        raw = headers.get("Retry-After") or headers.get("retry-after")
    except Exception:  # noqa: BLE001 — headers shape varies across SDKs
        return None
    if raw is None:
        return None

    raw = str(raw).strip()
    if not raw:
        return None

    # Integer or float seconds.
    try:
        seconds = float(raw)
        return max(0.0, seconds)
    except ValueError:
        pass

    # HTTP-date.
    try:
        target = parsedate_to_datetime(raw)
    except (TypeError, ValueError):
        return None
    if target is None:
        return None

    now = time.time()
    delta = target.timestamp() - now
    return max(0.0, delta)


def _compute_delay(
    attempt: int,
    config: RateLimitRetryConfig,
    retry_after: float | None,
) -> float:
    """Compute the sleep before the next retry.

    ``attempt`` is 1-based: ``1`` is the sleep after the first failure.
    """
    if retry_after is not None:
        return min(retry_after, config.max_delay)

    backoff = config.base_delay * (2 ** (attempt - 1))
    capped = min(backoff, config.max_delay)
    if config.jitter <= 0:
        return capped
    spread = capped * config.jitter
    return max(0.0, capped + random.uniform(-spread, spread))


async def retry_on_rate_limit(
    fn: Callable[[], Awaitable[T]],
    *,
    config: RateLimitRetryConfig | None = None,
    on_retry: OnRetry | None = None,
) -> T:
    """Invoke ``fn`` with rate-limit retries.

    The callable is awaited up to :attr:`RateLimitRetryConfig.max_attempts`
    times. Between attempts the helper sleeps for either ``Retry-After``
    (when the provider supplied it) or an exponentially backed-off value
    with jitter.

    Non-rate-limit exceptions propagate immediately. When the retry budget
    or :attr:`RateLimitRetryConfig.max_total_wait` is exhausted the last
    rate-limit exception is re-raised so the caller can classify it as
    :attr:`myrmec.agent.errors.ErrorCode.MODEL_RATE_LIMITED`.
    """
    cfg = config or RateLimitRetryConfig()
    total_waited = 0.0

    for attempt in range(1, cfg.max_attempts + 1):
        try:
            return await fn()
        except BaseException as exc:  # noqa: BLE001 — re-raised below
            if not is_rate_limit_error(exc):
                raise
            if attempt >= cfg.max_attempts:
                raise

            delay = _compute_delay(attempt, cfg, extract_retry_after(exc))
            remaining = max(0.0, cfg.max_total_wait - total_waited)
            if remaining <= 0:
                raise
            delay = min(delay, remaining)

            if on_retry is not None:
                try:
                    await on_retry(attempt, delay, exc)
                except Exception:  # noqa: BLE001 — observability must not break retry
                    logger.debug("on_retry callback raised", exc_info=True)

            await asyncio.sleep(delay)
            total_waited += delay

    # Unreachable: the loop either returns or re-raises.
    raise RuntimeError("retry_on_rate_limit exited without returning")

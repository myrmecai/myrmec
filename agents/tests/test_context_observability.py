"""Tests for TaskContext observability emission."""

from __future__ import annotations

from uuid import uuid4

import pytest

from myrmec.agent.context import TaskContext
from myrmec.agent.messages import MessageType, WebSocketMessage
from myrmec.agent.models import Task


def _make_task() -> Task:
    return Task(
        task_id=uuid4(),
        workflow_id=uuid4(),
        step_index=0,
        step_name="test-step",
        input={},
    )


class _Recorder:
    def __init__(self) -> None:
        self.messages: list[WebSocketMessage] = []

    async def __call__(self, msg: WebSocketMessage) -> None:
        self.messages.append(msg)


@pytest.mark.asyncio
async def test_report_token_usage_emits_event_and_accumulates() -> None:
    recorder = _Recorder()
    ctx = TaskContext(task=_make_task(), send_message=recorder)

    await ctx.report_token_usage(
        model="gpt-4o",
        prompt_tokens=10,
        completion_tokens=20,
        call_id="c1",
        duration_ms=123,
    )

    assert len(recorder.messages) == 1
    msg = recorder.messages[0]
    assert msg.type == MessageType.TOKEN_USAGE
    assert msg.payload["promptTokens"] == 10
    assert msg.payload["completionTokens"] == 20
    assert msg.payload["totalTokens"] == 30  # auto-summed
    assert msg.payload["model"] == "gpt-4o"

    # Accumulators advance.
    assert ctx._prompt_tokens == 10  # type: ignore[attr-defined]
    assert ctx._completion_tokens == 20  # type: ignore[attr-defined]
    assert ctx._total_tokens == 30  # type: ignore[attr-defined]


@pytest.mark.asyncio
async def test_emit_task_metrics_aggregates_and_is_idempotent() -> None:
    recorder = _Recorder()
    ctx = TaskContext(task=_make_task(), send_message=recorder)

    await ctx.report_token_usage(model="gpt-4o", prompt_tokens=5, completion_tokens=7)
    await ctx.report_token_usage(model="gpt-4o", prompt_tokens=3, completion_tokens=4)

    await ctx.emit_task_metrics()
    await ctx.emit_task_metrics()  # second call is a no-op

    metrics_msgs = [m for m in recorder.messages if m.type == MessageType.TASK_METRICS]
    assert len(metrics_msgs) == 1
    payload = metrics_msgs[0].payload
    assert payload["promptTokens"] == 8
    assert payload["completionTokens"] == 11
    assert payload["totalTokens"] == 19
    assert payload["model"] == "gpt-4o"
    assert payload["totalDurationMs"] is not None


@pytest.mark.asyncio
async def test_model_call_tracker_reports_usage_on_success() -> None:
    recorder = _Recorder()
    ctx = TaskContext(task=_make_task(), send_message=recorder)

    async with ctx.model_call(model="gpt-4o") as call:
        call.set_usage(prompt_tokens=2, completion_tokens=3, total_tokens=5)

    types = [m.type for m in recorder.messages]
    assert types == [MessageType.TOKEN_USAGE]
    payload = recorder.messages[0].payload
    assert payload["promptTokens"] == 2
    assert payload["completionTokens"] == 3
    assert payload["totalTokens"] == 5
    assert ctx._model_call_count == 1  # type: ignore[attr-defined]


@pytest.mark.asyncio
async def test_model_call_tracker_does_not_report_on_exception() -> None:
    recorder = _Recorder()
    ctx = TaskContext(task=_make_task(), send_message=recorder)

    with pytest.raises(RuntimeError):
        async with ctx.model_call(model="gpt-4o") as call:
            call.set_usage(prompt_tokens=1)
            raise RuntimeError("model exploded")

    # No token.usage event emitted on failure, but model_call_count still ticks.
    assert all(m.type != MessageType.TOKEN_USAGE for m in recorder.messages)
    assert ctx._model_call_count == 1  # type: ignore[attr-defined]

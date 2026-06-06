"""Tests for Phase 6b conversational protocol payload models."""

from __future__ import annotations

import json
from uuid import uuid4

import pytest

from myrmec.agent.messages import MessageType, WebSocketMessage
from myrmec.agent.models import (
    MessageCompletePayload,
    MessageDeltaPayload,
    TaskCancelledPayload,
)


def test_message_type_constants_match_engine_protocol() -> None:
    assert MessageType.MESSAGE_DELTA == "message.delta"
    assert MessageType.MESSAGE_COMPLETE == "message.complete"
    assert MessageType.TASK_CANCELLED == "task.cancelled"


def test_message_delta_camelcase_round_trip() -> None:
    conv_id = uuid4()
    payload = MessageDeltaPayload(
        conversation_id=conv_id, sequence_no=7, delta_index=2, content="Hello"
    )
    body = payload.model_dump(by_alias=True)
    assert body == {
        "conversationId": conv_id,
        "sequenceNo": 7,
        "deltaIndex": 2,
        "content": "Hello",
    }
    parsed = MessageDeltaPayload.model_validate(body)
    assert parsed == payload


def test_message_complete_with_token_count_optional() -> None:
    conv_id = uuid4()
    payload = MessageCompletePayload(
        conversation_id=conv_id,
        sequence_no=7,
        content="Hello, world.",
        model_code="github-gpt-4o",
    )
    body = payload.model_dump(by_alias=True, exclude_none=True)
    assert "tokenCount" not in body
    assert body["modelCode"] == "github-gpt-4o"


def test_task_cancelled_for_oneshot_omits_conversation_fields() -> None:
    payload = TaskCancelledPayload(task_id=uuid4(), reason="timeout")
    body = payload.model_dump(by_alias=True, exclude_none=True)
    assert "conversationId" not in body
    assert "sequenceNo" not in body
    assert "partialContent" not in body
    assert body["reason"] == "timeout"


def test_task_cancelled_for_conversational_includes_context() -> None:
    task_id = uuid4()
    conv_id = uuid4()
    payload = TaskCancelledPayload(
        task_id=task_id,
        conversation_id=conv_id,
        sequence_no=12,
        partial_content="Hello, w...",
        reason="user_request",
    )
    body = payload.model_dump(by_alias=True)
    assert body == {
        "taskId": task_id,
        "conversationId": conv_id,
        "sequenceNo": 12,
        "partialContent": "Hello, w...",
        "reason": "user_request",
    }


def test_websocket_message_wrap_message_complete() -> None:
    """The agent wraps payloads in ``WebSocketMessage.create``; verify the
    outer JSON envelope works for the new types."""
    conv_id = uuid4()
    payload = MessageCompletePayload(
        conversation_id=conv_id, sequence_no=0, content="hi"
    )
    msg = WebSocketMessage.create(MessageType.MESSAGE_COMPLETE, payload)
    encoded = msg.to_json()
    decoded = json.loads(encoded)
    assert decoded["type"] == "message.complete"
    assert decoded["payload"]["conversationId"] == str(conv_id)
    assert decoded["payload"]["content"] == "hi"


def test_message_delta_rejects_negative_sequence() -> None:
    with pytest.raises(ValueError):
        MessageDeltaPayload(
            conversation_id=uuid4(), sequence_no=-1, delta_index=0, content="x"
        )

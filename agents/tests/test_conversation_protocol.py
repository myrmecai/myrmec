"""Tests for Phase 6b conversational protocol payload models."""

from __future__ import annotations

import json
from uuid import uuid4

import pytest

from myrmec.agent.messages import MessageType, WebSocketMessage
from myrmec.agent.models import (
    ConversationHistoryEntry,
    ConversationTurnAssignPayload,
    MessageCompletePayload,
    MessageDeltaPayload,
    ModelInfo,
    TaskCancelledPayload,
)


def test_message_type_constants_match_engine_protocol() -> None:
    assert MessageType.MESSAGE_DELTA == "message.delta"
    assert MessageType.MESSAGE_COMPLETE == "message.complete"
    assert MessageType.TASK_CANCELLED == "task.cancelled"
    assert MessageType.CONVERSATION_TURN_ASSIGN == "conversation.turn.assign"


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


# ==================== Phase 6d \u2014 conversation.turn.assign ====================


def test_conversation_turn_assign_round_trip_full_envelope() -> None:
    """End-to-end: build the payload, wrap it as the engine does, and
    parse it back as the agent will at runtime. Catches alias drift +
    the ``populate_by_name`` config being missed."""
    conv_id = uuid4()
    project_id = uuid4()
    agent_id = uuid4()
    payload = ConversationTurnAssignPayload(
        conversation_id=conv_id,
        project_id=project_id,
        agent_id=agent_id,
        assistant_sequence_no=3,
        system_prompt="You are helpful.",
        pinned_facts="User prefers metric units.",
        history=[
            ConversationHistoryEntry(role="USER", content="Hi", sequence_no=0),
            ConversationHistoryEntry(role="ASSISTANT", content="Hello!", sequence_no=1),
            ConversationHistoryEntry(role="USER", content="How are you?", sequence_no=2),
        ],
        user_message="How are you?",
        timeout_seconds=120,
        model=ModelInfo(
            provider="openai", model_id="gpt-4o-mini", api_key="sk-test"
        ),
    )

    # 1) Server-side serialisation (camelCase, by_alias=True).
    body = payload.model_dump(by_alias=True, exclude_none=True)
    assert body["conversationId"] == conv_id
    assert body["assistantSequenceNo"] == 3
    assert body["userMessage"] == "How are you?"
    assert body["history"][2]["sequenceNo"] == 2
    assert body["model"]["modelId"] == "gpt-4o-mini"

    # 2) Round-trip through JSON exactly as the wire would carry it.
    encoded = json.dumps(body, default=str)
    decoded = json.loads(encoded)
    reparsed = ConversationTurnAssignPayload.model_validate(decoded)
    assert reparsed.conversation_id == conv_id
    assert reparsed.assistant_sequence_no == 3
    assert reparsed.system_prompt == "You are helpful."
    assert reparsed.pinned_facts == "User prefers metric units."
    assert len(reparsed.history) == 3
    assert reparsed.history[0].role == "USER"
    assert reparsed.history[0].sequence_no == 0
    assert reparsed.user_message == "How are you?"
    assert reparsed.timeout_seconds == 120
    assert reparsed.model is not None
    assert reparsed.model.provider == "openai"


def test_conversation_turn_assign_omits_optional_fields_when_unset() -> None:
    """Pinned facts, model handle, and prior history are all optional;
    the agent must not blow up when the engine ships a fresh
    conversation with just a system prompt."""
    payload = ConversationTurnAssignPayload(
        conversation_id=uuid4(),
        project_id=uuid4(),
        agent_id=uuid4(),
        assistant_sequence_no=0,
        system_prompt=None,
        user_message="first message",
    )
    body = payload.model_dump(by_alias=True, exclude_none=True)
    assert "pinnedFacts" not in body
    assert "model" not in body
    assert "systemPrompt" not in body
    assert body["history"] == []
    assert body["timeoutSeconds"] == 300  # default


def test_conversation_turn_assign_rejects_negative_assistant_sequence() -> None:
    with pytest.raises(ValueError):
        ConversationTurnAssignPayload(
            conversation_id=uuid4(),
            project_id=uuid4(),
            agent_id=uuid4(),
            assistant_sequence_no=-1,
            user_message="x",
        )


def test_conversation_turn_assign_wrapped_in_websocket_envelope() -> None:
    conv_id = uuid4()
    payload = ConversationTurnAssignPayload(
        conversation_id=conv_id,
        project_id=uuid4(),
        agent_id=uuid4(),
        assistant_sequence_no=0,
        user_message="ping",
    )
    msg = WebSocketMessage.create(MessageType.CONVERSATION_TURN_ASSIGN, payload)
    decoded = json.loads(msg.to_json())
    assert decoded["type"] == "conversation.turn.assign"
    assert decoded["payload"]["conversationId"] == str(conv_id)
    assert decoded["payload"]["userMessage"] == "ping"

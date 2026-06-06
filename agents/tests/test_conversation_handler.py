"""Tests for Phase 6e conversational turn handler on the agent side."""
from __future__ import annotations

from uuid import uuid4

import pytest

from myrmec.agent.conversation import EchoConversationTurnHandler
from myrmec.agent.messages import MessageType, WebSocketMessage
from myrmec.agent.models import (
    ConversationTurnAssignPayload,
    MessageCompletePayload,
    MessageDeltaPayload,
)


class _Recorder:
    """Captures every WebSocketMessage handed to it, in order."""

    def __init__(self) -> None:
        self.messages: list[WebSocketMessage] = []

    async def __call__(self, msg: WebSocketMessage) -> None:
        self.messages.append(msg)


def _payload(user_message: str = "Hello agent", *, seq: int = 5) -> ConversationTurnAssignPayload:
    return ConversationTurnAssignPayload(
        conversation_id=uuid4(),
        project_id=uuid4(),
        agent_id=uuid4(),
        assistant_sequence_no=seq,
        system_prompt="Be helpful.",
        user_message=user_message,
    )


@pytest.mark.asyncio
async def test_echo_handler_streams_deltas_then_complete_with_matching_sequence() -> None:
    handler = EchoConversationTurnHandler()
    payload = _payload("ping")
    recorder = _Recorder()

    await handler.execute(payload, recorder)

    # At least one delta + a final complete.
    assert len(recorder.messages) >= 2
    types = [m.type for m in recorder.messages]
    assert types[-1] == MessageType.MESSAGE_COMPLETE
    assert all(t == MessageType.MESSAGE_DELTA for t in types[:-1])

    # All frames carry the same conversation_id + sequence_no the engine pre-allocated.
    # NB: model_dump(by_alias=True) returns UUID objects, not strings \u2014 compare directly.
    for msg in recorder.messages:
        body = msg.payload
        assert body["conversationId"] == payload.conversation_id
        assert body["sequenceNo"] == payload.assistant_sequence_no

    # Delta indices monotonically increase from 0.
    delta_indices = [m.payload["deltaIndex"] for m in recorder.messages[:-1]]
    assert delta_indices == list(range(len(delta_indices)))

    # The concatenated delta content reconstructs the message.complete body.
    streamed = "".join(m.payload["content"] for m in recorder.messages[:-1])
    final = recorder.messages[-1].payload["content"]
    assert streamed == final
    assert payload.user_message in final


@pytest.mark.asyncio
async def test_echo_handler_round_trip_payloads_validate() -> None:
    """Engine-side deserialisation must succeed against the frames the
    handler emits. Catches any future drift in alias config."""
    handler = EchoConversationTurnHandler()
    recorder = _Recorder()
    payload = _payload("schema check", seq=12)

    await handler.execute(payload, recorder)

    for msg in recorder.messages[:-1]:
        # Validates camelCase aliases + numeric typing on deltaIndex/sequenceNo.
        MessageDeltaPayload.model_validate(msg.payload)
    MessageCompletePayload.model_validate(recorder.messages[-1].payload)


@pytest.mark.asyncio
async def test_echo_handler_template_can_be_customised() -> None:
    handler = EchoConversationTurnHandler(template="REPLY[{user_message}]")
    recorder = _Recorder()
    await handler.execute(_payload("xyz"), recorder)

    final = recorder.messages[-1].payload["content"]
    assert final == "REPLY[xyz]"

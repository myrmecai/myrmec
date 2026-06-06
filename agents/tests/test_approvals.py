"""Phase 7c \u2014 ApprovalClient + ConversationTurnContext.request_approval."""
from __future__ import annotations

import asyncio
import json
from uuid import UUID, uuid4

import pytest

from myrmec.agent.approvals import (
    ApprovalClient,
    ApprovalResult,
    ApprovalTimeoutError,
)
from myrmec.agent.conversation import ConversationTurnContext
from myrmec.agent.messages import MessageType, WebSocketMessage
from myrmec.agent.models import (
    ApprovalDecisionPayload,
    ApprovalRequestPayload,
    ConversationTurnAssignPayload,
)


class _Recorder:
    def __init__(self) -> None:
        self.messages: list[WebSocketMessage] = []

    async def __call__(self, msg: WebSocketMessage) -> None:
        self.messages.append(msg)


def _assign(conv_id: UUID) -> ConversationTurnAssignPayload:
    return ConversationTurnAssignPayload(
        conversation_id=conv_id,
        project_id=uuid4(),
        agent_id=uuid4(),
        assistant_sequence_no=1,
        user_message="proceed?",
    )


@pytest.mark.asyncio
async def test_request_approval_emits_well_formed_frame_with_client_id_in_payload() -> None:
    recorder = _Recorder()
    client = ApprovalClient(recorder)
    conv_id = uuid4()

    # Run the request and the resolve in parallel so we don't deadlock.
    async def _resolver() -> None:
        # Wait until the request frame is on the wire.
        for _ in range(50):
            if recorder.messages:
                break
            await asyncio.sleep(0.005)
        assert recorder.messages, "request frame was never sent"
        sent = ApprovalRequestPayload.model_validate(recorder.messages[0].payload)
        client.resolve(ApprovalDecisionPayload(
            conversation_id=conv_id,
            client_request_id=sent.client_request_id,
            decision="APPROVED",
            comment="ok by me",
        ))

    resolver = asyncio.create_task(_resolver())
    result = await client.request_approval(
        conversation_id=conv_id,
        content="run DROP TABLE",
        payload={"sql": "DROP TABLE users"},
        timeout=2.0,
    )
    await resolver

    assert result.approved is True
    assert result.comment == "ok by me"

    assert recorder.messages[0].type == MessageType.APPROVAL_REQUEST
    body = ApprovalRequestPayload.model_validate(recorder.messages[0].payload)
    assert body.conversation_id == conv_id
    assert body.content == "run DROP TABLE"
    # The SDK must always stamp the clientRequestId into the payload JSON
    # so the engine-side ApprovalDecisionDispatcher can recover it from
    # the persisted message even when the live WS frame is lost.
    parsed = json.loads(body.payload_json)
    assert parsed["clientRequestId"] == str(body.client_request_id)
    assert parsed["sql"] == "DROP TABLE users"


@pytest.mark.asyncio
async def test_request_approval_times_out_and_clears_pending() -> None:
    recorder = _Recorder()
    client = ApprovalClient(recorder)

    with pytest.raises(ApprovalTimeoutError):
        await client.request_approval(
            conversation_id=uuid4(),
            content="please decide",
            timeout=0.05,
        )
    # The timed-out future must be cleared so a late decision doesn't
    # silently leak memory.
    assert client.pending_count == 0


@pytest.mark.asyncio
async def test_resolve_returns_false_for_unknown_client_id() -> None:
    recorder = _Recorder()
    client = ApprovalClient(recorder)

    resolved = client.resolve(ApprovalDecisionPayload(
        conversation_id=uuid4(),
        client_request_id=uuid4(),
        decision="APPROVED",
    ))
    assert resolved is False


@pytest.mark.asyncio
async def test_context_request_approval_routes_through_client() -> None:
    """Smoke test the context wiring \u2014 the handler-facing API."""
    recorder = _Recorder()
    client = ApprovalClient(recorder)
    conv_id = uuid4()
    ctx = ConversationTurnContext(
        payload=_assign(conv_id),
        send_message=recorder,
        approval_client=client,
    )

    async def _resolver() -> None:
        for _ in range(50):
            if recorder.messages:
                break
            await asyncio.sleep(0.005)
        sent = ApprovalRequestPayload.model_validate(recorder.messages[0].payload)
        client.resolve(ApprovalDecisionPayload(
            conversation_id=conv_id,
            client_request_id=sent.client_request_id,
            decision="REJECTED",
            comment="too risky",
        ))

    resolver = asyncio.create_task(_resolver())
    result: ApprovalResult = await ctx.request_approval(
        content="apply migration",
        payload={"sql": "DROP COLUMN ..."},
        timeout=2.0,
    )
    await resolver

    assert result.rejected is True
    assert result.comment == "too risky"


@pytest.mark.asyncio
async def test_context_without_approval_client_raises() -> None:
    ctx = ConversationTurnContext(
        payload=_assign(uuid4()),
        send_message=_Recorder(),
        approval_client=None,
    )
    with pytest.raises(RuntimeError, match="ApprovalClient"):
        await ctx.request_approval(content="x")

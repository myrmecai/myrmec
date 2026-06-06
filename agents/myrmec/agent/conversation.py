"""Conversational-turn handling for the agent SDK (Phase 6e + 7c).

The engine pushes :class:`ConversationTurnAssignPayload` frames when a
USER message lands on a conversation pinned to this agent. The handler
contract here gives an SDK consumer a single place to plug in their LLM
call: produce a sequence of token chunks, the agent ships them as
``message.delta`` frames stamped with the engine-provided
``assistant_sequence_no``, and a final ``message.complete`` carries the
canonical text.

Phase 7c added :class:`ConversationTurnContext` so handlers can request
human approval mid-turn via ``await ctx.request_approval(...)``. The
context still exposes ``ctx.send`` for raw frame emission and
``ctx.payload`` for read-only access to the inbound assignment.

The default :class:`EchoConversationTurnHandler` ships a synthetic
reply token-by-token. It proves the engine→agent→broker→viewer loop
end-to-end without requiring an LLM provider, which is useful for E2E
tests under the deterministic profile and for the Phase 6e
chat-streaming Playwright spec. Real LLM integration is a drop-in
subclass that replaces the inner loop.
"""
from __future__ import annotations

import logging
import uuid
from datetime import datetime
from typing import Any, Awaitable, Callable

from myrmec.agent.approvals import ApprovalClient, ApprovalResult
from myrmec.agent.messages import MessageType, WebSocketMessage
from myrmec.agent.models import (
    ConversationTurnAssignPayload,
    MessageCompletePayload,
    MessageDeltaPayload,
)

logger = logging.getLogger(__name__)

SendMessage = Callable[[WebSocketMessage], Awaitable[None]]


class ConversationTurnContext:
    """Single object passed to :meth:`ConversationTurnHandler.execute`.

    Bundles the inbound turn payload, the WebSocket sender, and the
    shared :class:`ApprovalClient` so a handler can request HITL
    approval mid-turn without manually plumbing globals.
    """

    def __init__(
        self,
        payload: ConversationTurnAssignPayload,
        send_message: SendMessage,
        approval_client: ApprovalClient | None = None,
    ) -> None:
        self._payload = payload
        self._send_message = send_message
        self._approval_client = approval_client

    @property
    def payload(self) -> ConversationTurnAssignPayload:
        """The engine's turn assignment (read-only)."""
        return self._payload

    @property
    def conversation_id(self) -> uuid.UUID:
        return self._payload.conversation_id

    @property
    def assistant_sequence_no(self) -> int:
        return self._payload.assistant_sequence_no

    async def send(self, message: WebSocketMessage) -> None:
        """Emit a raw WebSocket frame back to the engine."""
        await self._send_message(message)

    async def request_approval(
        self,
        *,
        content: str | None = None,
        payload: dict[str, Any] | None = None,
        timeout: float | None = None,
        expires_at: datetime | None = None,
    ) -> ApprovalResult:
        """Block until a human approves or rejects the proposed action.

        Routed through the agent's shared :class:`ApprovalClient`. Raises
        :class:`myrmec.agent.approvals.ApprovalTimeoutError` if no
        decision arrives within ``timeout`` seconds.
        """
        if self._approval_client is None:
            raise RuntimeError(
                "ConversationTurnContext was created without an ApprovalClient \u2014 "
                "the Agent should always supply one in production. Construct the "
                "context with approval_client=ApprovalClient(send_message) in tests."
            )
        return await self._approval_client.request_approval(
            conversation_id=self._payload.conversation_id,
            content=content,
            payload=payload,
            timeout=timeout,
            expires_at=expires_at,
        )


class ConversationTurnHandler:
    """Abstract contract for producing one assistant turn.

    Subclasses override :meth:`execute` to run a real LLM. The base
    class doubles as the no-op default by raising
    :class:`NotImplementedError` \u2014 use :class:`EchoConversationTurnHandler`
    if you want a sensible default.
    """

    async def execute(self, ctx: ConversationTurnContext) -> None:  # pragma: no cover - abstract
        raise NotImplementedError(
            "ConversationTurnHandler.execute must be overridden by a real "
            "subclass (or use EchoConversationTurnHandler for tests)."
        )


class EchoConversationTurnHandler(ConversationTurnHandler):
    """Streams a canned reply so the wire path can be exercised in tests.

    The handler emits one ``message.delta`` per word in the synthetic
    reply, then a ``message.complete`` carrying the full text. The
    ``assistant_sequence_no`` from the assignment payload is echoed
    verbatim on every outbound frame so live viewers can stitch the
    stream back together.
    """

    def __init__(self, template: str = "Echo from agent: {user_message}") -> None:
        self._template = template

    async def execute(self, ctx: ConversationTurnContext) -> None:
        payload = ctx.payload
        reply = self._template.format(user_message=payload.user_message)
        words = reply.split(" ")
        delta_index = 0
        for word in words:
            chunk = word + (" " if word != words[-1] else "")
            delta = MessageDeltaPayload(
                conversation_id=payload.conversation_id,
                sequence_no=payload.assistant_sequence_no,
                delta_index=delta_index,
                content=chunk,
            )
            await ctx.send(WebSocketMessage.create(
                MessageType.MESSAGE_DELTA,
                delta.model_dump(by_alias=True),
            ))
            delta_index += 1

        complete = MessageCompletePayload(
            conversation_id=payload.conversation_id,
            sequence_no=payload.assistant_sequence_no,
            content=reply,
        )
        await ctx.send(WebSocketMessage.create(
            MessageType.MESSAGE_COMPLETE,
            complete.model_dump(by_alias=True),
        ))
        logger.debug(
            "Echo handler streamed %d delta(s) for conversation %s seq %s",
            delta_index, payload.conversation_id, payload.assistant_sequence_no,
        )

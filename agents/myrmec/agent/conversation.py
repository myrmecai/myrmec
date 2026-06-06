"""Conversational-turn handling for the agent SDK (Phase 6e).

The engine pushes :class:`ConversationTurnAssignPayload` frames when a
USER message lands on a conversation pinned to this agent. The handler
contract here gives an SDK consumer a single place to plug in their LLM
call: produce a sequence of token chunks, the agent ships them as
``message.delta`` frames stamped with the engine-provided
``assistant_sequence_no``, and a final ``message.complete`` carries the
canonical text.

The default :class:`EchoConversationTurnHandler` ships a synthetic
reply token-by-token. It proves the engine→agent→broker→viewer loop
end-to-end without requiring an LLM provider, which is useful for E2E
tests under the deterministic profile and for the Phase 6e
chat-streaming Playwright spec. Real LLM integration is a drop-in
subclass that replaces the inner loop.
"""
from __future__ import annotations

import logging
from typing import Awaitable, Callable

from myrmec.agent.messages import MessageType, WebSocketMessage
from myrmec.agent.models import (
    ConversationTurnAssignPayload,
    MessageCompletePayload,
    MessageDeltaPayload,
)

logger = logging.getLogger(__name__)

SendMessage = Callable[[WebSocketMessage], Awaitable[None]]


class ConversationTurnHandler:
    """Abstract contract for producing one assistant turn.

    Subclasses override :meth:`execute` to run a real LLM. The base
    class doubles as the no-op default by raising
    :class:`NotImplementedError` — use :class:`EchoConversationTurnHandler`
    if you want a sensible default.
    """

    async def execute(
        self,
        payload: ConversationTurnAssignPayload,
        send_message: SendMessage,
    ) -> None:  # pragma: no cover - abstract
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

    async def execute(
        self,
        payload: ConversationTurnAssignPayload,
        send_message: SendMessage,
    ) -> None:
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
            await send_message(WebSocketMessage.create(
                MessageType.MESSAGE_DELTA,
                delta.model_dump(by_alias=True),
            ))
            delta_index += 1

        complete = MessageCompletePayload(
            conversation_id=payload.conversation_id,
            sequence_no=payload.assistant_sequence_no,
            content=reply,
        )
        await send_message(WebSocketMessage.create(
            MessageType.MESSAGE_COMPLETE,
            complete.model_dump(by_alias=True),
        ))
        logger.debug(
            "Echo handler streamed %d delta(s) for conversation %s seq %s",
            delta_index, payload.conversation_id, payload.assistant_sequence_no,
        )

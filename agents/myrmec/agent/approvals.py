"""HITL approval client (Phase 7c).

Lives between an in-flight conversational turn and the engine: the
agent's ``ctx.request_approval(...)`` call posts an
``approval.request`` frame, registers a pending future keyed by the
client-generated request id, and the agent's inbound message router
resolves that future when the matching ``approval.decision`` frame
arrives.

Wired by :class:`myrmec.agent.agent.Agent` — a single
:class:`ApprovalClient` is created on connect and shared by every
in-flight turn. Tests can instantiate it directly with a mock
``send_message`` callable.
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime
from typing import Any, Awaitable, Callable

from pydantic import BaseModel, Field

from myrmec.agent.messages import MessageType, WebSocketMessage
from myrmec.agent.models import ApprovalDecisionPayload, ApprovalRequestPayload

logger = logging.getLogger(__name__)

SendMessage = Callable[[WebSocketMessage], Awaitable[None]]


class ApprovalResult(BaseModel):
    """Outcome of a ``ctx.request_approval(...)`` call."""

    decision: str
    """One of APPROVED / REJECTED / EXPIRED."""

    comment: str | None = None
    """Human-supplied comment captured at decision time (may be empty)."""

    request_message_id: uuid.UUID | None = None
    response_message_id: uuid.UUID | None = None
    approver_user_id: uuid.UUID | None = None
    raw_payload: dict[str, Any] | None = Field(default=None, exclude=True)

    @property
    def approved(self) -> bool:
        return self.decision == "APPROVED"

    @property
    def rejected(self) -> bool:
        return self.decision == "REJECTED"

    @property
    def expired(self) -> bool:
        return self.decision == "EXPIRED"


class ApprovalTimeoutError(asyncio.TimeoutError):
    """Raised by ``request_approval`` when no decision arrived in time."""


class ApprovalClient:
    """Coordinates the request/response correlation for HITL approvals.

    Thread-safety: one instance per agent. ``request_approval`` is
    coroutine-safe — multiple in-flight conversations can each call it
    concurrently and the per-id futures keep them isolated.
    """

    def __init__(self, send_message: SendMessage) -> None:
        self._send_message = send_message
        self._pending: dict[uuid.UUID, asyncio.Future[ApprovalDecisionPayload]] = {}
        self._lock = asyncio.Lock()

    async def request_approval(
        self,
        *,
        conversation_id: uuid.UUID,
        content: str | None = None,
        payload: dict[str, Any] | None = None,
        timeout: float | None = None,
        expires_at: datetime | None = None,
    ) -> ApprovalResult:
        """Send an ``approval.request`` frame and await the decision.

        Args:
            conversation_id: The conversation to attach the request to.
            content: Short human-readable summary rendered on the card.
            payload: Free-form dict serialised to ``payloadJson``. The
                SDK always injects ``clientRequestId`` so the
                engine-side dispatcher can correlate decisions even if
                the WS handler only sees the persisted row's JSON.
            timeout: Seconds to await a decision. ``None`` blocks
                indefinitely (typically a bad idea — callers should
                pass an explicit budget aligned with their LLM turn
                budget).
            expires_at: Optional engine-side deadline; the scheduled
                sweeper (Phase 7e) marks the request EXPIRED past
                this. Independent of ``timeout`` which only bounds the
                SDK's wait.
        """
        client_request_id = uuid.uuid4()
        future: asyncio.Future[ApprovalDecisionPayload] = asyncio.get_running_loop().create_future()
        async with self._lock:
            self._pending[client_request_id] = future

        # Make sure the engine-side dispatcher can recover our id from
        # the persisted message even though we also stamp it on the WS
        # frame. The dispatcher's extract-from-payloadJson path is the
        # contract that lets a reconnecting agent eventually pick the
        # decision up via REST.
        body = dict(payload or {})
        body.setdefault("clientRequestId", str(client_request_id))

        payload_json = _safe_dumps(body)

        frame = ApprovalRequestPayload(
            conversation_id=conversation_id,
            client_request_id=client_request_id,
            content=content,
            payload_json=payload_json,
            expires_at=expires_at,
        )
        try:
            await self._send_message(WebSocketMessage.create(
                MessageType.APPROVAL_REQUEST,
                frame.model_dump(by_alias=True, mode="json"),
            ))
            logger.info(
                "Requested approval for conv %s clientId %s",
                conversation_id, client_request_id,
            )
            decision = await asyncio.wait_for(future, timeout=timeout)
        except asyncio.TimeoutError as e:
            async with self._lock:
                self._pending.pop(client_request_id, None)
            if not future.done():
                future.cancel()
            raise ApprovalTimeoutError(
                f"No approval decision for conv {conversation_id} clientId {client_request_id} "
                f"within {timeout}s"
            ) from e
        finally:
            async with self._lock:
                self._pending.pop(client_request_id, None)

        return ApprovalResult(
            decision=decision.decision,
            comment=decision.comment,
            request_message_id=decision.request_message_id,
            response_message_id=decision.response_message_id,
            approver_user_id=decision.approver_user_id,
        )

    def resolve(self, payload: ApprovalDecisionPayload) -> bool:
        """Called by the agent's message router on incoming decisions.

        Returns ``True`` if a matching pending future was resolved,
        ``False`` if the decision didn't correlate to anything we were
        waiting on (treated as a benign late delivery).
        """
        if payload.client_request_id is None:
            logger.warning(
                "Received approval.decision without clientRequestId (conv %s) \u2014 cannot route",
                payload.conversation_id,
            )
            return False
        future = self._pending.get(payload.client_request_id)
        if future is None:
            logger.debug(
                "approval.decision for unknown clientId %s (conv %s) \u2014 likely a late delivery",
                payload.client_request_id, payload.conversation_id,
            )
            return False
        if future.done():
            logger.debug(
                "approval.decision for already-resolved clientId %s \u2014 ignoring duplicate",
                payload.client_request_id,
            )
            return False
        future.set_result(payload)
        return True

    @property
    def pending_count(self) -> int:
        return len(self._pending)


def _safe_dumps(obj: dict[str, Any]) -> str:
    """Defensive JSON dump so a non-serialisable value in ``payload``
    can't break the wire format — fall back to ``str(obj)`` on error
    rather than raising mid-turn."""
    import json
    try:
        return json.dumps(obj, default=str, separators=(",", ":"))
    except Exception:  # noqa: BLE001
        logger.exception("Failed to serialise approval payload; falling back to str()")
        return json.dumps({"raw": str(obj)})

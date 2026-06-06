"""
WebSocket message types and utilities for the Myrmec protocol.
"""

from datetime import datetime, timezone
from typing import Any, Generic, TypeVar

from pydantic import BaseModel, Field


class MessageType:
    """WebSocket message types for Engine ↔ Agent communication."""
    
    # Engine → Agent
    TASK_ASSIGN = "task.assign"
    TASK_CANCEL = "task.cancel"
    PING = "ping"
    
    # Agent → Engine
    TASK_ACCEPT = "task.accept"
    TASK_REJECT = "task.reject"
    TASK_PROGRESS = "task.progress"
    TASK_COMPLETE = "task.complete"
    TASK_FAILED = "task.failed"
    LOG = "log"
    TOOL_CALL = "tool.call"
    TOOL_RESULT = "tool.result"
    TOKEN_USAGE = "token.usage"
    TASK_METRICS = "task.metrics"
    PONG = "pong"
    DISCONNECT = "disconnect"


T = TypeVar("T")


class WebSocketMessage(BaseModel, Generic[T]):
    """Base WebSocket message format."""
    type: str
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    payload: T
    
    class Config:
        populate_by_name = True
    
    def to_json(self) -> str:
        """Serialize to JSON string."""
        return self.model_dump_json(by_alias=True)
    
    @classmethod
    def create(cls, type: str, payload: Any) -> "WebSocketMessage":
        """Create a message with the given type and payload."""
        return cls(
            type=type,
            timestamp=datetime.now(timezone.utc),
            payload=payload,
        )


class CloseCode:
    """WebSocket close codes for agent communication."""
    
    NORMAL = 1000  # Normal closure
    GOING_AWAY = 1001  # Server shutdown
    TOKEN_EXPIRED = 4001  # Token expired - refresh and reconnect
    INVALID_TOKEN = 4002  # Invalid token - re-register
    AGENT_DEACTIVATED = 4003  # Agent disabled by admin
    DUPLICATE_CONNECTION = 4004  # Another instance connected

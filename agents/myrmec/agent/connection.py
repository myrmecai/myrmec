"""
WebSocket connection manager for agent-engine communication.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Callable, Coroutine

import websockets
from websockets.client import ClientConnection
from websockets.connection import State
from websockets.exceptions import ConnectionClosed

from myrmec.agent.messages import CloseCode, MessageType, WebSocketMessage

logger = logging.getLogger(__name__)


class WebSocketConnection:
    """
    WebSocket connection to the Engine.
    
    Handles:
    - Connecting with JWT token
    - Receiving messages and dispatching to handlers
    - Sending messages
    - Reconnection with exponential backoff
    - Ping/pong keep-alive
    """
    
    def __init__(
        self,
        engine_url: str,
        on_message: Callable[[dict[str, Any]], Coroutine[Any, Any, None]],
        on_connect: Callable[[], Coroutine[Any, Any, None]] | None = None,
        on_disconnect: Callable[[int, str], Coroutine[Any, Any, None]] | None = None,
    ):
        """
        Initialize WebSocket connection.
        
        Args:
            engine_url: Base URL of the Engine (http:// will be converted to ws://).
            on_message: Callback for received messages.
            on_connect: Callback when connection is established.
            on_disconnect: Callback when connection is closed (code, reason).
        """
        self._engine_url = engine_url
        self._on_message = on_message
        self._on_connect = on_connect
        self._on_disconnect = on_disconnect
        
        self._ws: ClientConnection | None = None
        self._access_token: str | None = None
        self._running = False
        self._receive_task: asyncio.Task | None = None
    
    @property
    def is_connected(self) -> bool:
        """Check if WebSocket is connected."""
        return self._ws is not None and self._ws.state == State.OPEN
    
    async def connect(self, access_token: str) -> None:
        """
        Connect to the Engine WebSocket.
        
        Args:
            access_token: JWT access token for authentication.
        """
        self._access_token = access_token
        ws_url = self._get_ws_url(access_token)
        
        logger.info("Connecting to WebSocket: %s", ws_url.split("?")[0])
        
        self._ws = await websockets.connect(
            ws_url,
            ping_interval=None,  # We handle ping/pong at protocol level
            ping_timeout=None,
            close_timeout=5,
        )
        
        self._running = True
        
        if self._on_connect:
            await self._on_connect()
        
        logger.info("WebSocket connected")
    
    async def disconnect(self, reason: str = "Agent shutdown") -> None:
        """
        Gracefully disconnect from the Engine.
        
        Args:
            reason: Reason for disconnection.
        """
        self._running = False
        
        if self._ws and self._ws.state == State.OPEN:
            # Send disconnect message
            try:
                await self.send_message(WebSocketMessage.create(
                    MessageType.DISCONNECT,
                    {"reason": reason},
                ))
            except Exception:
                pass
            
            # Close connection
            try:
                await self._ws.close(CloseCode.NORMAL, reason)
            except Exception:
                pass
        
        if self._receive_task:
            self._receive_task.cancel()
            try:
                await self._receive_task
            except asyncio.CancelledError:
                pass
        
        self._ws = None
        logger.info("WebSocket disconnected: %s", reason)
    
    async def send_message(self, message: WebSocketMessage) -> None:
        """
        Send a message to the Engine.
        
        Args:
            message: WebSocket message to send.
        
        Raises:
            ConnectionError: If not connected.
        """
        if not self._ws or not self._ws.state == State.OPEN:
            raise ConnectionError("WebSocket not connected")
        
        json_str = message.to_json()
        await self._ws.send(json_str)
        logger.debug("Sent message: %s", message.type)
    
    async def receive_loop(self) -> None:
        """
        Main receive loop - processes incoming messages.
        
        This should be run as a task. It will run until disconnect.
        """
        if not self._ws:
            raise ConnectionError("WebSocket not connected")
        
        try:
            async for raw_message in self._ws:
                if not self._running:
                    break
                
                try:
                    data = json.loads(raw_message)
                    msg_type = data.get("type", "unknown")
                    
                    # Handle ping at connection level
                    if msg_type == MessageType.PING:
                        await self._handle_ping()
                    else:
                        await self._on_message(data)
                        
                except json.JSONDecodeError as e:
                    logger.error("Invalid JSON message: %s", e)
                except Exception as e:
                    logger.error("Error processing message: %s", e)
                    
        except ConnectionClosed as e:
            logger.info("WebSocket closed: code=%s reason=%s", e.code, e.reason)
            if self._on_disconnect:
                await self._on_disconnect(e.code, e.reason or "")
        except Exception as e:
            logger.error("WebSocket error: %s", e)
            if self._on_disconnect:
                await self._on_disconnect(CloseCode.GOING_AWAY, str(e))
        finally:
            self._running = False
    
    async def _handle_ping(self) -> None:
        """Respond to ping with pong."""
        try:
            await self.send_message(WebSocketMessage.create(MessageType.PONG, {}))
            logger.debug("Responded to ping with pong")
        except Exception as e:
            logger.error("Failed to send pong: %s", e)
    
    def _get_ws_url(self, access_token: str) -> str:
        """Convert HTTP URL to WebSocket URL with token."""
        base_url = self._engine_url.rstrip("/")
        
        # Convert http(s):// to ws(s)://
        if base_url.startswith("https://"):
            ws_url = "wss://" + base_url[8:]
        elif base_url.startswith("http://"):
            ws_url = "ws://" + base_url[7:]
        else:
            ws_url = "ws://" + base_url
        
        return f"{ws_url}/api/v1/agent/ws?token={access_token}"


class ReconnectingConnection:
    """
    WebSocket connection with automatic reconnection.
    
    Wraps WebSocketConnection and adds:
    - Exponential backoff reconnection
    - Token refresh on 4001 (token expired)
    - Re-registration on 4002 (invalid token)
    """
    
    def __init__(
        self,
        connection: WebSocketConnection,
        get_access_token: Callable[[], Coroutine[Any, Any, str]],
        refresh_token: Callable[[], Coroutine[Any, Any, str]],
        register: Callable[[], Coroutine[Any, Any, str]],
        max_backoff: float = 32.0,
    ):
        """
        Initialize reconnecting connection.
        
        Args:
            connection: Underlying WebSocket connection.
            get_access_token: Coroutine to get current access token.
            refresh_token: Coroutine to refresh token (returns new access token).
            register: Coroutine to re-register (returns new access token).
            max_backoff: Maximum backoff time in seconds.
        """
        self._connection = connection
        self._get_access_token = get_access_token
        self._refresh_token = refresh_token
        self._register = register
        self._max_backoff = max_backoff
        
        self._should_reconnect = True
        self._backoff = 1.0
    
    async def connect_with_retry(self) -> None:
        """Connect with automatic retry on failure."""
        while self._should_reconnect:
            try:
                token = await self._get_access_token()
                await self._connection.connect(token)
                self._backoff = 1.0  # Reset backoff on success
                return
            except Exception as e:
                logger.warning("Connection failed: %s, retrying in %.1fs", e, self._backoff)
                await asyncio.sleep(self._backoff)
                self._backoff = min(self._backoff * 2, self._max_backoff)
    
    async def handle_disconnect(self, code: int, reason: str) -> bool:
        """
        Handle disconnection and determine if should reconnect.
        
        Args:
            code: WebSocket close code.
            reason: Close reason.
        
        Returns:
            True if should attempt reconnection.
        """
        if not self._should_reconnect:
            return False
        
        if code == CloseCode.NORMAL:
            logger.info("Normal disconnect, not reconnecting")
            return False
        
        if code == CloseCode.TOKEN_EXPIRED:
            logger.info("Token expired, refreshing...")
            try:
                await self._refresh_token()
            except Exception as e:
                logger.warning("Token refresh failed: %s, re-registering", e)
                await self._register()
        
        elif code == CloseCode.INVALID_TOKEN:
            logger.info("Invalid token, re-registering...")
            await self._register()
        
        elif code == CloseCode.AGENT_DEACTIVATED:
            logger.error("Agent deactivated by admin, not reconnecting")
            self._should_reconnect = False
            return False
        
        elif code == CloseCode.DUPLICATE_CONNECTION:
            logger.warning("Duplicate connection, another instance may be running")
            # Still try to reconnect - could be a race condition
        
        return True
    
    def stop(self) -> None:
        """Stop reconnection attempts."""
        self._should_reconnect = False

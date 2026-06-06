"""
Agent logging handler and output capture utilities.

Captures Python logging and stdout/stderr and streams them to the Engine.
"""

from __future__ import annotations

import asyncio
import io
import logging
import sys
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import TYPE_CHECKING, Any, Callable, Coroutine, TextIO

from myrmec.agent.messages import MessageType, WebSocketMessage
from myrmec.agent.models import LogPayload, LogSource

if TYPE_CHECKING:
    from uuid import UUID


class AgentLoggingHandler(logging.Handler):
    """
    Logging handler that sends Python logging output to the Engine.
    
    This handler captures all Python logging module output (from libraries,
    LangChain, etc.) and streams it to the Engine as AGENT source logs.
    
    Example:
        handler = AgentLoggingHandler(task_id, send_func)
        logging.root.addHandler(handler)
        
        # Now any library logging is captured:
        logging.getLogger("langchain").info("Processing...")
    """
    
    # Map Python log levels to our standard levels
    _LEVEL_MAP = {
        logging.DEBUG: "DEBUG",
        logging.INFO: "INFO",
        logging.WARNING: "WARN",
        logging.WARN: "WARN",
        logging.ERROR: "ERROR",
        logging.CRITICAL: "ERROR",
    }
    
    def __init__(
        self,
        task_id: "UUID",
        send_message: Callable[[WebSocketMessage], Coroutine[Any, Any, None]],
        loop: asyncio.AbstractEventLoop | None = None,
    ):
        """
        Initialize the handler.
        
        Args:
            task_id: Current task ID for log attribution.
            send_message: Async function to send WebSocket messages.
            loop: Event loop to use for async sends. If None, tries to get current loop.
        """
        super().__init__()
        self._task_id = task_id
        self._send_message = send_message
        self._loop = loop
        
        # Set formatter to include logger name
        self.setFormatter(logging.Formatter("%(name)s: %(message)s"))
    
    def emit(self, record: logging.LogRecord) -> None:
        """Emit a log record by sending to Engine."""
        try:
            level = self._LEVEL_MAP.get(record.levelno, "INFO")
            message = self.format(record)
            
            payload = LogPayload(
                task_id=self._task_id,
                level=level,
                message=message,
                data={
                    "logger": record.name,
                    "source_file": record.filename,
                    "line_number": record.lineno,
                },
                timestamp=datetime.now(timezone.utc),
                source=LogSource.AGENT.value,
            )
            
            msg = WebSocketMessage.create(MessageType.LOG, payload.model_dump(by_alias=True))
            
            # Try to send asynchronously
            loop = self._loop or asyncio.get_event_loop()
            if loop.is_running():
                asyncio.create_task(self._send_message(msg))
            else:
                loop.run_until_complete(self._send_message(msg))
                
        except Exception:
            # Don't raise from logging handler - would cause infinite loops
            self.handleError(record)
    
    def set_task_id(self, task_id: "UUID") -> None:
        """Update the task ID for subsequent logs."""
        self._task_id = task_id


class OutputCapture:
    """
    Context manager that captures stdout/stderr and streams to Engine.
    
    This captures print() statements and unhandled output from libraries
    that write directly to stdout/stderr.
    
    Example:
        async with OutputCapture(task_id, send_func):
            print("This goes to Engine as SYSTEM source")
            subprocess.run(["echo", "As does this"])
    """
    
    def __init__(
        self,
        task_id: "UUID",
        send_message: Callable[[WebSocketMessage], Coroutine[Any, Any, None]],
        capture_stdout: bool = True,
        capture_stderr: bool = True,
    ):
        """
        Initialize output capture.
        
        Args:
            task_id: Current task ID for log attribution.
            send_message: Async function to send WebSocket messages.
            capture_stdout: Whether to capture stdout.
            capture_stderr: Whether to capture stderr.
        """
        self._task_id = task_id
        self._send_message = send_message
        self._capture_stdout = capture_stdout
        self._capture_stderr = capture_stderr
        
        self._original_stdout: TextIO | None = None
        self._original_stderr: TextIO | None = None
    
    async def __aenter__(self) -> "OutputCapture":
        """Start capturing output."""
        if self._capture_stdout:
            self._original_stdout = sys.stdout
            sys.stdout = _StreamCapture(
                self._task_id,
                self._send_message,
                "INFO",
                self._original_stdout,
            )
        
        if self._capture_stderr:
            self._original_stderr = sys.stderr
            sys.stderr = _StreamCapture(
                self._task_id,
                self._send_message,
                "ERROR",
                self._original_stderr,
            )
        
        return self
    
    async def __aexit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> bool:
        """Stop capturing and restore original streams."""
        if self._original_stdout is not None:
            sys.stdout = self._original_stdout
        
        if self._original_stderr is not None:
            sys.stderr = self._original_stderr
        
        return False  # Don't suppress exceptions
    
    def set_task_id(self, task_id: "UUID") -> None:
        """Update the task ID for subsequent output."""
        self._task_id = task_id
        if isinstance(sys.stdout, _StreamCapture):
            sys.stdout.set_task_id(task_id)
        if isinstance(sys.stderr, _StreamCapture):
            sys.stderr.set_task_id(task_id)


class _StreamCapture(io.TextIOBase):
    """
    Wrapper around a stream that captures writes and sends to Engine.
    
    Also passes through to the original stream for local visibility.
    """
    
    def __init__(
        self,
        task_id: "UUID",
        send_message: Callable[[WebSocketMessage], Coroutine[Any, Any, None]],
        level: str,
        original: TextIO,
    ):
        self._task_id = task_id
        self._send_message = send_message
        self._level = level
        self._original = original
        self._buffer = ""
    
    def write(self, data: str) -> int:
        """Write data, buffering until newline."""
        # Always pass through to original
        self._original.write(data)
        
        # Buffer and send complete lines
        self._buffer += data
        while "\n" in self._buffer:
            line, self._buffer = self._buffer.split("\n", 1)
            if line.strip():  # Don't send empty lines
                self._send_line(line)
        
        return len(data)
    
    def flush(self) -> None:
        """Flush any remaining buffer and original stream."""
        if self._buffer.strip():
            self._send_line(self._buffer)
            self._buffer = ""
        self._original.flush()
    
    def _send_line(self, line: str) -> None:
        """Send a line to the Engine."""
        payload = LogPayload(
            task_id=self._task_id,
            level=self._level,
            message=line,
            data=None,
            timestamp=datetime.now(timezone.utc),
            source=LogSource.SYSTEM.value,
        )
        
        msg = WebSocketMessage.create(MessageType.LOG, payload.model_dump(by_alias=True))
        
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                asyncio.create_task(self._send_message(msg))
            else:
                loop.run_until_complete(self._send_message(msg))
        except RuntimeError:
            # No event loop available - skip sending
            pass
    
    def set_task_id(self, task_id: "UUID") -> None:
        """Update the task ID."""
        self._task_id = task_id
    
    # Delegate commonly used methods to original stream
    @property
    def encoding(self) -> str:
        return self._original.encoding
    
    @property
    def errors(self) -> str | None:
        return self._original.errors
    
    def isatty(self) -> bool:
        return self._original.isatty()
    
    def readable(self) -> bool:
        return False
    
    def writable(self) -> bool:
        return True
    
    def seekable(self) -> bool:
        return False

"""
TaskContext - provides utilities for task execution.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from time import perf_counter
from typing import TYPE_CHECKING, Any, Callable, Coroutine

from myrmec.agent.models import (
    LogLevel,
    LogPayload,
    LogSource,
    TaskMetricsPayload,
    TaskProgressPayload,
    TokenUsagePayload,
    ToolCallPayload,
    ToolResultPayload,
)
from myrmec.agent.messages import MessageType, WebSocketMessage

if TYPE_CHECKING:
    from myrmec.agent.models import Task


class TaskContext:
    """
    Context object for task execution.
    
    Provides utilities for:
    - Logging messages (debug, info, warn, error)
    - Reporting progress (0-100%)
    - Tracking tool calls for audit
    
    The context is created by the Agent and passed to the executor.
    """
    
    def __init__(
        self,
        task: "Task",
        send_message: Callable[[WebSocketMessage], Coroutine[Any, Any, None]],
    ):
        """
        Initialize task context.
        
        Args:
            task: The task being executed.
            send_message: Coroutine to send WebSocket messages.
        """
        self._task = task
        self._send_message = send_message
        self._cancelled = False

        # Observability accumulators (populated via report_token_usage /
        # model_call() / track_tool_call()). Aggregated and emitted via
        # _emit_task_metrics() near task completion.
        self._start_time: float = perf_counter()
        self._model_duration_ms: int = 0
        self._tool_duration_ms: int = 0
        self._model_call_count: int = 0
        self._tool_call_count: int = 0
        self._prompt_tokens: int = 0
        self._completion_tokens: int = 0
        self._total_tokens: int = 0
        self._primary_model: str | None = None
        self._metrics_emitted: bool = False
    
    @property
    def task(self) -> "Task":
        """The task being executed."""
        return self._task
    
    @property
    def is_cancelled(self) -> bool:
        """Check if the task has been cancelled."""
        return self._cancelled
    
    def mark_cancelled(self) -> None:
        """Mark the task as cancelled (called by Agent on cancel message)."""
        self._cancelled = True
    
    # ==================== Logging ====================
    
    async def log(
        self,
        level: LogLevel,
        message: str,
        data: dict[str, Any] | None = None,
        source: LogSource = LogSource.TASK,
    ) -> None:
        """
        Send a log message to the Engine.
        
        Args:
            level: Log level (DEBUG, INFO, WARN, ERROR).
            message: Log message.
            data: Optional structured data.
            source: Source of the log (TASK, AGENT, or SYSTEM).
        """
        payload = LogPayload(
            task_id=self._task.task_id,
            level=level.value,
            message=message,
            data=data,
            timestamp=datetime.now(timezone.utc),
            source=source.value,
        )
        msg = WebSocketMessage.create(MessageType.LOG, payload.model_dump(by_alias=True))
        await self._send_message(msg)
    
    async def log_debug(self, message: str, data: dict[str, Any] | None = None) -> None:
        """Log a debug message."""
        await self.log(LogLevel.DEBUG, message, data)
    
    async def log_info(self, message: str, data: dict[str, Any] | None = None) -> None:
        """Log an info message."""
        await self.log(LogLevel.INFO, message, data)
    
    async def log_warn(self, message: str, data: dict[str, Any] | None = None) -> None:
        """Log a warning message."""
        await self.log(LogLevel.WARN, message, data)
    
    async def log_error(self, message: str, data: dict[str, Any] | None = None) -> None:
        """Log an error message."""
        await self.log(LogLevel.ERROR, message, data)
    
    # ==================== Progress ====================
    
    async def report_progress(self, progress: int, message: str | None = None) -> None:
        """
        Report task progress.
        
        Args:
            progress: Progress percentage (0-100).
            message: Optional progress message.
        """
        progress = max(0, min(100, progress))  # Clamp to 0-100
        
        payload = TaskProgressPayload(
            task_id=self._task.task_id,
            progress=progress,
            message=message,
        )
        msg = WebSocketMessage.create(MessageType.TASK_PROGRESS, payload.model_dump(by_alias=True))
        await self._send_message(msg)
    
    # ==================== Tool Tracking ====================
    
    async def track_tool_call(
        self,
        tool_name: str,
        input: dict[str, Any],
    ) -> "ToolCallTracker":
        """
        Start tracking a tool call for audit purposes.
        
        Use as context manager:
            async with await context.track_tool_call("search", {"query": "..."}) as tracker:
                result = await search(query)
                tracker.set_output(result)
        
        Args:
            tool_name: Name of the tool being called.
            input: Input parameters to the tool.
        
        Returns:
            ToolCallTracker for recording the result.
        """
        call_id = str(uuid.uuid4())
        
        # Send tool.call message
        payload = ToolCallPayload(
            task_id=self._task.task_id,
            tool_name=tool_name,
            call_id=call_id,
            input=input,
        )
        msg = WebSocketMessage.create(MessageType.TOOL_CALL, payload.model_dump(by_alias=True))
        await self._send_message(msg)
        
        return ToolCallTracker(
            task_id=self._task.task_id,
            call_id=call_id,
            send_message=self._send_message,
            on_exit=self._record_tool_call,
        )

    # ==================== Observability ====================

    def model_call(self, model: str | None = None, call_id: str | None = None) -> "ModelCallTracker":
        """Track timing for a single LLM call.

        Use as ``async with context.model_call(model_id) as call: ...``.
        Token usage discovered after the call should be reported via
        :meth:`ModelCallTracker.set_usage` (or directly via
        :meth:`report_token_usage`).
        """
        return ModelCallTracker(context=self, model=model, call_id=call_id)

    async def report_token_usage(
        self,
        *,
        model: str | None = None,
        prompt_tokens: int | None = None,
        completion_tokens: int | None = None,
        total_tokens: int | None = None,
        call_id: str | None = None,
        duration_ms: int | None = None,
    ) -> None:
        """Send a ``token.usage`` event and update accumulators.

        All token counts are optional; the engine treats missing values as zero.
        Negative values are clamped to zero so accidental ``-1`` placeholders
        don't poison aggregates.
        """
        prompt_tokens = max(0, prompt_tokens) if prompt_tokens is not None else None
        completion_tokens = max(0, completion_tokens) if completion_tokens is not None else None
        if total_tokens is None and (prompt_tokens or completion_tokens):
            total_tokens = (prompt_tokens or 0) + (completion_tokens or 0)
        total_tokens = max(0, total_tokens) if total_tokens is not None else None

        if prompt_tokens:
            self._prompt_tokens += prompt_tokens
        if completion_tokens:
            self._completion_tokens += completion_tokens
        if total_tokens:
            self._total_tokens += total_tokens
        if model and not self._primary_model:
            self._primary_model = model

        payload = TokenUsagePayload(
            task_id=self._task.task_id,
            model=model,
            call_id=call_id,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            total_tokens=total_tokens,
            duration_ms=duration_ms,
        )
        msg = WebSocketMessage.create(MessageType.TOKEN_USAGE, payload.model_dump(by_alias=True))
        await self._send_message(msg)

    def _record_model_call(self, duration_ms: int) -> None:
        self._model_call_count += 1
        self._model_duration_ms += max(0, duration_ms)

    def _record_tool_call(self, duration_ms: int) -> None:
        self._tool_call_count += 1
        self._tool_duration_ms += max(0, duration_ms)

    async def emit_task_metrics(self) -> None:
        """Send a ``task.metrics`` summary message. Idempotent."""
        if self._metrics_emitted:
            return
        self._metrics_emitted = True

        total_duration_ms = int((perf_counter() - self._start_time) * 1000)
        payload = TaskMetricsPayload(
            task_id=self._task.task_id,
            total_duration_ms=total_duration_ms,
            model_duration_ms=self._model_duration_ms or None,
            tool_duration_ms=self._tool_duration_ms or None,
            model_call_count=self._model_call_count or None,
            tool_call_count=self._tool_call_count or None,
            prompt_tokens=self._prompt_tokens or None,
            completion_tokens=self._completion_tokens or None,
            total_tokens=self._total_tokens or None,
            model=self._primary_model,
        )
        msg = WebSocketMessage.create(MessageType.TASK_METRICS, payload.model_dump(by_alias=True))
        await self._send_message(msg)


class ToolCallTracker:
    """
    Tracks a tool call for audit purposes.
    
    Use as async context manager to automatically record timing and send result.
    """
    
    def __init__(
        self,
        task_id: Any,
        call_id: str,
        send_message: Callable[[WebSocketMessage], Coroutine[Any, Any, None]],
        on_exit: Callable[[int], None] | None = None,
    ):
        self._task_id = task_id
        self._call_id = call_id
        self._send_message = send_message
        self._on_exit = on_exit
        self._start_time: float | None = None
        self._output: dict[str, Any] | None = None
        self._error: str | None = None
    
    def set_output(self, output: dict[str, Any]) -> None:
        """Set the tool output."""
        self._output = output
    
    def set_error(self, error: str) -> None:
        """Set error if tool failed."""
        self._error = error
    
    async def __aenter__(self) -> "ToolCallTracker":
        self._start_time = perf_counter()
        return self
    
    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        duration_ms = int((perf_counter() - (self._start_time or 0)) * 1000)
        
        # If exception occurred and no error set, capture it
        if exc_val is not None and self._error is None:
            self._error = str(exc_val)
        
        # Send tool.result message
        payload = ToolResultPayload(
            task_id=self._task_id,
            call_id=self._call_id,
            output=self._output,
            duration_ms=duration_ms,
            error=self._error,
        )
        msg = WebSocketMessage.create(MessageType.TOOL_RESULT, payload.model_dump(by_alias=True))
        await self._send_message(msg)

        if self._on_exit is not None:
            try:
                self._on_exit(duration_ms)
            except Exception:  # noqa: BLE001 — observability must never break execution
                pass


class ModelCallTracker:
    """Async context manager that times a single LLM invocation and lets the
    caller report token usage discovered during/after the call."""

    def __init__(
        self,
        context: "TaskContext",
        model: str | None = None,
        call_id: str | None = None,
    ):
        self._context = context
        self._model = model
        self._call_id = call_id or str(uuid.uuid4())
        self._start_time: float | None = None
        self._reported: bool = False
        self._pending: dict[str, Any] | None = None

    @property
    def call_id(self) -> str:
        return self._call_id

    def set_model(self, model: str) -> None:
        self._model = model

    def set_usage(
        self,
        *,
        prompt_tokens: int | None = None,
        completion_tokens: int | None = None,
        total_tokens: int | None = None,
        model: str | None = None,
    ) -> None:
        """Record token counts to be emitted on context exit.

        Multiple calls accumulate (useful for streamed responses).
        """
        if model:
            self._model = model
        pending = self._pending or {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        }
        if prompt_tokens:
            pending["prompt_tokens"] += int(prompt_tokens)
        if completion_tokens:
            pending["completion_tokens"] += int(completion_tokens)
        if total_tokens:
            pending["total_tokens"] += int(total_tokens)
        self._pending = pending

    async def __aenter__(self) -> "ModelCallTracker":
        self._start_time = perf_counter()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        duration_ms = int((perf_counter() - (self._start_time or 0)) * 1000)
        # Record model duration even on failure so we don't lose the signal.
        self._context._record_model_call(duration_ms)

        if exc_val is None and self._pending is not None and not self._reported:
            self._reported = True
            try:
                await self._context.report_token_usage(
                    model=self._model,
                    prompt_tokens=self._pending.get("prompt_tokens") or None,
                    completion_tokens=self._pending.get("completion_tokens") or None,
                    total_tokens=self._pending.get("total_tokens") or None,
                    call_id=self._call_id,
                    duration_ms=duration_ms,
                )
            except Exception:  # noqa: BLE001 — observability must never break execution
                pass

"""
Main Agent class - the entry point for running an agent.
"""

from __future__ import annotations

import asyncio
import logging
import signal
from typing import Any
from uuid import UUID

from myrmec.agent.connection import ReconnectingConnection, WebSocketConnection
from myrmec.agent.context import TaskContext
from myrmec.agent.errors import classify_exception
from myrmec.agent.executor import TaskExecutor
from myrmec.agent.http_client import EngineHttpClient
from myrmec.agent.logging_handler import AgentLoggingHandler, OutputCapture
from myrmec.agent.messages import CloseCode, MessageType, WebSocketMessage
from myrmec.agent.models import (
    Task,
    TaskAcceptPayload,
    TaskAssignPayload,
    TaskCompletePayload,
    TaskFailedPayload,
    TaskRejectPayload,
)

logger = logging.getLogger(__name__)


class Agent:
    """
    Main Agent class for connecting to the Myrmec Control Plane.
    
    The Agent handles:
    - Registration with the Engine
    - WebSocket connection management
    - Token refresh and reconnection
    - Task acceptance and execution dispatch
    - Graceful shutdown
    
    Usage:
        class MyExecutor(TaskExecutor):
            async def execute(self, task: Task, context: TaskContext) -> TaskResult:
                # Your task logic
                return TaskResult(output={"result": "done"})
        
        agent = Agent(
            engine_url="http://localhost:8080",
            registration_key="myr_agent_...",
            executor=MyExecutor(),
        )
        await agent.run()
    """
    
    def __init__(
        self,
        engine_url: str,
        registration_key: str,
        executor: TaskExecutor,
        metadata: dict[str, Any] | None = None,
        sdk_version: str = "0.1.0",
        capture_logging: bool = True,
        capture_output: bool = True,
    ):
        """
        Initialize the Agent.
        
        Args:
            engine_url: Base URL of the Engine (e.g., "http://localhost:8080").
            registration_key: Agent registration key (starts with "myr_agent_").
            executor: TaskExecutor implementation for handling tasks.
            metadata: Optional metadata about this agent instance.
            sdk_version: SDK version to report to Engine.
            capture_logging: If True, capture Python logging output as AGENT logs.
            capture_output: If True, capture stdout/stderr as SYSTEM logs.
        """
        self._engine_url = engine_url
        self._registration_key = registration_key
        self._executor = executor
        self._metadata = metadata
        self._sdk_version = sdk_version
        
        # Clients
        self._http_client = EngineHttpClient(
            engine_url=engine_url,
            registration_key=registration_key,
            sdk_version=sdk_version,
        )
        
        self._ws_connection: WebSocketConnection | None = None
        self._reconnecting: ReconnectingConnection | None = None
        
        # State
        self._instance_id: UUID | None = None
        self._access_token: str | None = None
        self._refresh_token: str | None = None
        
        self._current_task: Task | None = None
        self._current_context: TaskContext | None = None
        self._task_execution: asyncio.Task | None = None
        
        # Logging capture options
        self._capture_logging = capture_logging
        self._capture_output = capture_output
        self._logging_handler: AgentLoggingHandler | None = None
        self._output_capture: OutputCapture | None = None
        
        self._running = False
        self._shutdown_event = asyncio.Event()
    
    @property
    def instance_id(self) -> UUID | None:
        """Agent instance ID assigned by Engine."""
        return self._instance_id
    
    @property
    def is_running(self) -> bool:
        """Check if agent is running."""
        return self._running
    
    @property
    def is_busy(self) -> bool:
        """Check if agent is executing a task."""
        return self._current_task is not None
    
    async def run(self) -> None:
        """
        Run the agent.
        
        This is the main entry point. It will:
        1. Register with the Engine
        2. Connect via WebSocket
        3. Process tasks until shutdown
        
        The method blocks until shutdown is requested.
        """
        logger.info("Starting agent...")
        self._running = True
        
        # Setup signal handlers for graceful shutdown
        self._setup_signal_handlers()
        
        try:
            # Register with Engine
            await self._register()
            
            # Initialize executor
            await self._executor.on_startup()
            
            # Create WebSocket connection
            self._ws_connection = WebSocketConnection(
                engine_url=self._engine_url,
                on_message=self._handle_message,
                on_connect=self._on_connect,
                on_disconnect=self._on_disconnect,
            )
            
            self._reconnecting = ReconnectingConnection(
                connection=self._ws_connection,
                get_access_token=self._get_access_token,
                refresh_token=self._refresh_tokens,
                register=self._re_register,
            )
            
            # Connect and run
            await self._reconnecting.connect_with_retry()
            
            # Main loop - receive messages until shutdown
            while self._running:
                try:
                    await self._ws_connection.receive_loop()
                    
                    # If we get here, connection closed
                    if not self._running:
                        break
                    
                    # Attempt reconnection
                    await self._reconnecting.connect_with_retry()
                    
                except asyncio.CancelledError:
                    break
                except Exception as e:
                    logger.error("Unexpected error in receive loop: %s", e)
                    if self._running:
                        await asyncio.sleep(1)
            
        except Exception as e:
            logger.error("Agent error: %s", e)
            raise
        finally:
            await self._cleanup()
    
    async def shutdown(self, reason: str = "Shutdown requested") -> None:
        """
        Request graceful shutdown.
        
        Args:
            reason: Reason for shutdown.
        """
        logger.info("Shutdown requested: %s", reason)
        self._running = False
        
        if self._reconnecting:
            self._reconnecting.stop()
        
        if self._ws_connection:
            await self._ws_connection.disconnect(reason)
        
        self._shutdown_event.set()
    
    # ==================== Registration ====================
    
    async def _register(self) -> None:
        """Register with the Engine."""
        logger.info("Registering with Engine: %s", self._engine_url)
        
        response = await self._http_client.register(metadata=self._metadata)
        
        self._instance_id = response.instance_id
        self._access_token = response.access_token
        self._refresh_token = response.refresh_token
        
        logger.info("Registered as instance: %s", self._instance_id)
    
    async def _re_register(self) -> str:
        """Re-register and return new access token."""
        await self._register()
        return self._access_token
    
    async def _refresh_tokens(self) -> str:
        """Refresh tokens and return new access token."""
        logger.debug("Refreshing tokens...")
        
        response = await self._http_client.refresh(self._refresh_token)
        
        self._access_token = response.access_token
        self._refresh_token = response.refresh_token
        
        logger.debug("Tokens refreshed")
        return self._access_token
    
    async def _get_access_token(self) -> str:
        """Get current access token."""
        return self._access_token
    
    # ==================== Message Handling ====================
    
    async def _handle_message(self, data: dict[str, Any]) -> None:
        """Handle incoming WebSocket message."""
        msg_type = data.get("type", "unknown")
        payload = data.get("payload", {})
        
        logger.debug("Received message: %s", msg_type)
        
        if msg_type == MessageType.TASK_ASSIGN:
            await self._handle_task_assign(payload)
        elif msg_type == MessageType.TASK_CANCEL:
            await self._handle_task_cancel(payload)
        else:
            logger.warning("Unknown message type: %s", msg_type)
    
    async def _handle_task_assign(self, payload: dict[str, Any]) -> None:
        """Handle task assignment from Engine."""
        try:
            task_payload = TaskAssignPayload.model_validate(payload)
            task = task_payload.to_task()
        except Exception as e:
            logger.error("Invalid task.assign payload: %s", e)
            return
        
        # Check if busy
        if self.is_busy:
            logger.warning("Rejecting task %s: already executing a task", task.task_id)
            await self._reject_task(task.task_id, "Agent is busy")
            return
        
        # Accept the task
        await self._accept_task(task)
    
    async def _handle_task_cancel(self, payload: dict[str, Any]) -> None:
        """Handle task cancellation request."""
        task_id = UUID(payload.get("taskId", ""))
        reason = payload.get("reason", "Cancelled by Engine")
        
        if self._current_task and self._current_task.task_id == task_id:
            logger.info("Cancelling task %s: %s", task_id, reason)
            
            # Mark context as cancelled
            if self._current_context:
                self._current_context.mark_cancelled()
            
            # Notify executor
            await self._executor.on_cancel(self._current_task, reason)
            
            # Cancel the execution task
            if self._task_execution and not self._task_execution.done():
                self._task_execution.cancel()
        else:
            logger.warning("Received cancel for unknown task: %s", task_id)
    
    # ==================== Task Execution ====================
    
    async def _accept_task(self, task: Task) -> None:
        """Accept and start executing a task."""
        logger.info("Accepting task %s (%s)", task.task_id, task.step_name)
        
        # Send accept message
        accept = TaskAcceptPayload(task_id=task.task_id)
        await self._send_message(WebSocketMessage.create(
            MessageType.TASK_ACCEPT,
            accept.model_dump(by_alias=True),
        ))
        
        # Set up task state
        self._current_task = task
        self._current_context = TaskContext(
            task=task,
            send_message=self._send_message,
        )
        
        # Start execution in background
        self._task_execution = asyncio.create_task(
            self._execute_task(task, self._current_context)
        )
    
    async def _reject_task(self, task_id: UUID, reason: str) -> None:
        """Reject a task assignment."""
        reject = TaskRejectPayload(task_id=task_id, reason=reason)
        await self._send_message(WebSocketMessage.create(
            MessageType.TASK_REJECT,
            reject.model_dump(by_alias=True),
        ))
    
    async def _execute_task(self, task: Task, context: TaskContext) -> None:
        """Execute a task using the executor."""
        try:
            logger.info("Executing task %s", task.task_id)
            
            # Set up logging capture for this task
            await self._setup_logging_capture(task.task_id)
            
            result = await self._executor.execute(task, context)
            
            # Check if cancelled during execution
            if context.is_cancelled:
                logger.info("Task %s was cancelled", task.task_id)
                return
            
            # Emit aggregated metrics before completion so the engine can
            # persist them alongside the task outcome.
            try:
                await context.emit_task_metrics()
            except Exception:  # noqa: BLE001
                logger.warning("Failed to emit task metrics for %s", task.task_id, exc_info=True)

            # Send completion
            complete = TaskCompletePayload(
                task_id=task.task_id,
                result=result.output,
            )
            await self._send_message(WebSocketMessage.create(
                MessageType.TASK_COMPLETE,
                complete.model_dump(by_alias=True),
            ))
            
            logger.info("Task %s completed", task.task_id)
            
        except asyncio.CancelledError:
            logger.info("Task %s execution cancelled", task.task_id)
            
        except Exception as e:
            logger.error("Task %s failed: %s", task.task_id, e)

            # Emit aggregated metrics before failure so partial signal is
            # captured even when the task ultimately errors out.
            try:
                await context.emit_task_metrics()
            except Exception:  # noqa: BLE001
                logger.warning("Failed to emit task metrics for %s", task.task_id, exc_info=True)

            # Send failure
            failed = TaskFailedPayload(
                task_id=task.task_id,
                error=str(e),
                error_code=classify_exception(e).value,
            )
            await self._send_message(WebSocketMessage.create(
                MessageType.TASK_FAILED,
                failed.model_dump(by_alias=True),
            ))
        
        finally:
            # Tear down logging capture
            await self._teardown_logging_capture()
            
            # Clear task state
            self._current_task = None
            self._current_context = None
            self._task_execution = None
    
    # ==================== Connection Callbacks ====================
    
    async def _on_connect(self) -> None:
        """Called when WebSocket connects."""
        logger.info("WebSocket connected, ready for tasks")
    
    async def _on_disconnect(self, code: int, reason: str) -> None:
        """Called when WebSocket disconnects."""
        logger.info("WebSocket disconnected: code=%s reason=%s", code, reason)
        
        # Let reconnecting handler decide what to do
        if self._reconnecting:
            should_reconnect = await self._reconnecting.handle_disconnect(code, reason)
            if not should_reconnect:
                await self.shutdown("Connection closed permanently")
    
    # ==================== Utilities ====================
    
    async def _send_message(self, message: WebSocketMessage) -> None:
        """Send a WebSocket message."""
        if self._ws_connection:
            await self._ws_connection.send_message(message)
    
    async def _cleanup(self) -> None:
        """Cleanup resources."""
        logger.info("Cleaning up...")
        
        # Shutdown executor
        try:
            await self._executor.on_shutdown()
        except Exception as e:
            logger.error("Executor shutdown error: %s", e)
        
        # Close HTTP client
        await self._http_client.close()
        
        logger.info("Agent stopped")
    
    # ==================== Logging Capture ====================
    
    async def _setup_logging_capture(self, task_id: UUID) -> None:
        """Set up logging and output capture for a task."""
        if self._capture_logging:
            self._logging_handler = AgentLoggingHandler(
                task_id=task_id,
                send_message=self._send_message,
                loop=asyncio.get_event_loop(),
            )
            # Add to root logger to capture all logging output
            logging.root.addHandler(self._logging_handler)
            logger.debug("Installed logging capture for task %s", task_id)
        
        if self._capture_output:
            self._output_capture = OutputCapture(
                task_id=task_id,
                send_message=self._send_message,
            )
            await self._output_capture.__aenter__()
            logger.debug("Installed output capture for task %s", task_id)
    
    async def _teardown_logging_capture(self) -> None:
        """Remove logging and output capture."""
        if self._logging_handler:
            logging.root.removeHandler(self._logging_handler)
            self._logging_handler = None
        
        if self._output_capture:
            await self._output_capture.__aexit__(None, None, None)
            self._output_capture = None

    def _setup_signal_handlers(self) -> None:
        """Setup signal handlers for graceful shutdown."""
        try:
            loop = asyncio.get_running_loop()
            
            for sig in (signal.SIGINT, signal.SIGTERM):
                loop.add_signal_handler(
                    sig,
                    lambda: asyncio.create_task(self.shutdown(f"Received signal {sig.name}")),
                )
        except NotImplementedError:
            # Windows doesn't support add_signal_handler
            pass

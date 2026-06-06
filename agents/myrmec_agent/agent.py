"""Main Agent class for Myrmec."""

import logging
import signal
import sys
from typing import Any, Optional

from myrmec_agent.client import ControlPlaneClient
from myrmec_agent.heartbeat import HeartbeatTask

logger = logging.getLogger(__name__)


class Agent:
    """Myrmec Agent that connects to the Control Plane."""

    def __init__(
        self,
        name: str,
        control_plane_url: str,
        registration_key: str,
        capabilities: Optional[list[str]] = None,
        heartbeat_interval: float = 30.0,
    ):
        self.name = name
        self.control_plane_url = control_plane_url
        self.registration_key = registration_key
        self.capabilities = capabilities or []
        self.heartbeat_interval = heartbeat_interval

        self._client = ControlPlaneClient(control_plane_url)
        self._heartbeat_task: Optional[HeartbeatTask] = None
        self._agent_id: Optional[str] = None
        self._running = False

    @property
    def agent_id(self) -> Optional[str]:
        """Get the agent ID after registration."""
        return self._agent_id

    def _register(self) -> None:
        """Register with the control plane."""
        metadata: dict[str, Any] = {
            "version": "0.1.0",
            "capabilities": self.capabilities,
        }

        logger.info(f"Registering agent '{self.name}' with control plane...")
        response = self._client.register(
            registration_key=self.registration_key,
            name=self.name,
            metadata=metadata,
        )

        self._agent_id = response.agent_id
        self._client.set_tokens(response.access_token, response.refresh_token)
        logger.info(f"Agent registered successfully (ID: {self._agent_id})")

    def _send_heartbeat(self) -> bool:
        """Send a heartbeat to the control plane."""
        try:
            self._client.heartbeat(status="ONLINE")
            return True
        except Exception as e:
            logger.error(f"Failed to send heartbeat: {e}")
            return False

    def _setup_signal_handlers(self) -> None:
        """Setup graceful shutdown handlers."""
        def handler(signum, frame):
            logger.info(f"Received signal {signum}, shutting down...")
            self.stop()
            sys.exit(0)

        signal.signal(signal.SIGINT, handler)
        signal.signal(signal.SIGTERM, handler)

    def start(self) -> None:
        """
        Start the agent.
        
        This will:
        1. Register with the control plane
        2. Start the heartbeat background task
        3. Block until stop() is called or signal received
        """
        if self._running:
            logger.warning("Agent already running")
            return

        self._setup_signal_handlers()

        # Register
        self._register()

        # Start heartbeat
        self._heartbeat_task = HeartbeatTask(
            send_heartbeat=self._send_heartbeat,
            interval=self.heartbeat_interval,
        )
        self._heartbeat_task.start()

        self._running = True
        logger.info(f"Agent '{self.name}' is now running")

        # Block until stopped
        try:
            while self._running and self._heartbeat_task.is_running:
                signal.pause() if hasattr(signal, 'pause') else self._heartbeat_task._stop_event.wait(1.0)
        except KeyboardInterrupt:
            pass
        finally:
            self.stop()

    def stop(self) -> None:
        """Stop the agent gracefully."""
        if not self._running:
            return

        self._running = False

        if self._heartbeat_task:
            self._heartbeat_task.stop()
            self._heartbeat_task = None

        self._client.close()
        logger.info(f"Agent '{self.name}' stopped")

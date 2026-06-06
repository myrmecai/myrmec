"""Background heartbeat task for Myrmec Agent."""

import threading
import time
import logging
from typing import Optional, Callable

logger = logging.getLogger(__name__)


class HeartbeatTask:
    """Background task that sends periodic heartbeats."""

    def __init__(
        self,
        send_heartbeat: Callable[[], bool],
        interval: float = 30.0,
        max_retries: int = 3,
    ):
        self.send_heartbeat = send_heartbeat
        self.interval = interval
        self.max_retries = max_retries
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()

    def start(self) -> None:
        """Start the heartbeat background task."""
        if self._running:
            logger.warning("Heartbeat task already running")
            return

        self._running = True
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        logger.info(f"Heartbeat task started (interval: {self.interval}s)")

    def stop(self) -> None:
        """Stop the heartbeat background task."""
        if not self._running:
            return

        self._running = False
        self._stop_event.set()

        if self._thread:
            self._thread.join(timeout=5.0)
            self._thread = None

        logger.info("Heartbeat task stopped")

    def _run(self) -> None:
        """Main heartbeat loop."""
        consecutive_failures = 0

        while self._running and not self._stop_event.is_set():
            try:
                success = self.send_heartbeat()
                if success:
                    consecutive_failures = 0
                    logger.debug("Heartbeat sent successfully")
                else:
                    consecutive_failures += 1
                    logger.warning(f"Heartbeat failed (attempt {consecutive_failures}/{self.max_retries})")
            except Exception as e:
                consecutive_failures += 1
                logger.error(f"Heartbeat error: {e}")

            if consecutive_failures >= self.max_retries:
                logger.error("Max heartbeat retries exceeded, stopping")
                self._running = False
                break

            # Wait for next interval or stop signal
            self._stop_event.wait(timeout=self.interval)

    @property
    def is_running(self) -> bool:
        """Check if heartbeat task is running."""
        return self._running

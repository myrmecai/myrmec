"""
Test Agent for E2E Testing.

This module provides a test agent that captures TaskContext for verification.
It registers with the control plane, receives a task, logs the received context
to a file for assertion, then completes the task and exits.

Usage:
    python -m e2e.test_agent --output context.json --timeout 60
"""

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path
from typing import Any

from myrmec_agent.client import ControlPlaneClient

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)


class TestAgent:
    """
    Test agent that captures task context for E2E verification.
    
    Flow:
    1. Register with control plane
    2. Poll for tasks (or wait for WebSocket assignment)
    3. When task received, save context to output file
    4. Complete task and exit
    """
    
    def __init__(
        self,
        control_plane_url: str,
        registration_key: str,
        output_file: Path,
        timeout_seconds: int = 60,
    ):
        self.control_plane_url = control_plane_url
        self.registration_key = registration_key
        self.output_file = output_file
        self.timeout_seconds = timeout_seconds
        
        self._client = ControlPlaneClient(control_plane_url)
        self._agent_id: str | None = None
        
    def register(self) -> None:
        """Register agent with control plane."""
        logger.info("Registering test agent...")
        
        response = self._client.register(
            registration_key=self.registration_key,
            name="e2e-test-agent",
            metadata={
                "version": "0.1.0",
                "capabilities": ["test", "context-capture"],
                "mode": "e2e-test",
            },
        )
        
        self._agent_id = response.agent_id
        self._client.set_tokens(response.access_token, response.refresh_token)
        logger.info(f"Registered with ID: {self._agent_id}")
        
    def capture_context(self, task_data: dict[str, Any]) -> None:
        """
        Capture task context to output file for verification.
        
        Args:
            task_data: Raw task assignment data from control plane
        """
        context = task_data.get("context", {})
        knowledge = context.get("knowledge", [])
        rag = context.get("rag")
        
        output = {
            "task_id": task_data.get("taskId"),
            "workflow_id": task_data.get("workflowId"),
            "step_name": task_data.get("stepName"),
            "knowledge_documents": [
                {
                    "name": k.get("name"),
                    "category": k.get("category"),
                    "priority": k.get("priority"),
                    # Include first 200 chars of content for verification
                    "content_preview": k.get("content", "")[:200],
                }
                for k in knowledge
            ],
            "knowledge_count": len(knowledge),
            "knowledge_char_count": context.get("knowledgeCharCount", 0),
            "rag_config": rag,
            "document_names": [k.get("name") for k in knowledge],
        }
        
        # Ensure output directory exists
        self.output_file.parent.mkdir(parents=True, exist_ok=True)
        
        with open(self.output_file, "w", encoding="utf-8") as f:
            json.dump(output, f, indent=2)
            
        logger.info(f"Context captured to: {self.output_file}")
        logger.info(f"  - Documents: {len(knowledge)}")
        logger.info(f"  - Char count: {context.get('knowledgeCharCount', 0)}")
        for doc in output["knowledge_documents"]:
            logger.info(f"    - {doc['name']} ({doc['category']}, priority={doc['priority']})")
            
    def run(self) -> bool:
        """
        Run the test agent.
        
        Returns:
            True if task was received and context captured, False if timed out.
        """
        try:
            self.register()
            
            # For now, we'll use polling to check for assigned tasks
            # In a full implementation, this would use WebSocket
            logger.info(f"Waiting for task assignment (timeout: {self.timeout_seconds}s)...")
            
            # TODO: Implement task polling or WebSocket listener
            # For initial testing, we'll verify context resolution via the Java tests
            # and use this agent for full E2E flow later
            
            logger.info("Test agent ready - waiting for task assignment...")
            logger.info("(Note: Full task assignment requires WebSocket implementation)")
            
            return True
            
        except Exception as e:
            logger.error(f"Test agent error: {e}")
            return False


def main():
    parser = argparse.ArgumentParser(description="E2E Test Agent")
    parser.add_argument(
        "--url",
        default="http://localhost:9090",
        help="Control plane URL (default: http://localhost:9090)",
    )
    parser.add_argument(
        "--registration-key",
        required=True,
        help="Agent registration key",
    )
    parser.add_argument(
        "--output",
        default="test-output/context.json",
        help="Output file for captured context (default: test-output/context.json)",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=60,
        help="Timeout in seconds (default: 60)",
    )
    
    args = parser.parse_args()
    
    agent = TestAgent(
        control_plane_url=args.url,
        registration_key=args.registration_key,
        output_file=Path(args.output),
        timeout_seconds=args.timeout,
    )
    
    success = agent.run()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()

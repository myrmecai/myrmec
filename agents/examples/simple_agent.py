#!/usr/bin/env python3
"""
Simple Myrmec Agent Example

This example demonstrates how to create and run an agent that:
1. Connects to the Engine via WebSocket
2. Receives tasks with prompts from Agent Profile and Workflow
3. Uses model credentials provided via the task (from Model configuration)
4. Executes tasks using LangChain with tools
5. Sends live logs back to the Engine

Tool System:
- Constructor tools: Always available (defined in this file)
- Task tools: Dynamically loaded from Engine's task.tools (e.g., read_file, write_file)
- File tools require workspace_path to be set on the executor

Usage:
    export MYRMEC_REGISTRATION_KEY=myr_agent_xxx
    export MYRMEC_WORKSPACE_PATH=/path/to/workspace  # Optional: for file tools
    python simple_agent.py
    
The agent receives API credentials from the Engine (stored with the Model).
No local API keys are needed.
"""

import asyncio
import logging
import os
from datetime import datetime

from langchain_core.tools import tool

from myrmec.agent import Agent
from myrmec.agent.langchain import LangChainExecutor

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

logger = logging.getLogger(__name__)


# ==================== Define Tools ====================

@tool
def get_current_time() -> str:
    """Get the current date and time."""
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


@tool
def calculate(expression: str) -> str:
    """
    Evaluate a mathematical expression.
    
    Args:
        expression: A mathematical expression like "2 + 2" or "sqrt(16)"
    """
    import math
    # Safe evaluation with limited functions
    allowed_names = {
        "abs": abs, "round": round, "min": min, "max": max,
        "sum": sum, "pow": pow, "sqrt": math.sqrt,
        "sin": math.sin, "cos": math.cos, "tan": math.tan,
        "log": math.log, "log10": math.log10, "exp": math.exp,
        "pi": math.pi, "e": math.e,
    }
    try:
        result = eval(expression, {"__builtins__": {}}, allowed_names)
        return str(result)
    except Exception as e:
        return f"Error: {e}"


@tool
def search_knowledge(query: str) -> str:
    """
    Search for information in the knowledge base.
    
    Args:
        query: Search query
    """
    # In a real implementation, this would query a vector database
    # For demo purposes, return a placeholder
    return f"Search results for '{query}': [No external knowledge base configured]"


# ==================== Main ====================

async def main():
    # Configuration from environment
    engine_url = os.environ.get("MYRMEC_ENGINE_URL", "http://localhost:9090")
    registration_key = os.environ.get("MYRMEC_REGISTRATION_KEY")
    workspace_path = os.environ.get("MYRMEC_WORKSPACE_PATH")

    if not registration_key:
        print("Error: MYRMEC_REGISTRATION_KEY environment variable is required")
        print("Create an agent with a registration key in the control plane first")
        exit(1)

    # Create executor with tools
    # - Constructor tools (get_current_time, calculate) are always available
    # - Task tools from Engine (read_file, write_file, etc.) are loaded dynamically
    # - File tools require workspace_path to restrict file operations
    executor = LangChainExecutor(
        tools=[get_current_time, calculate, search_knowledge],
        max_iterations=15,
        workspace_path=workspace_path,  # Required for file tools
    )
    
    # Create agent
    agent = Agent(
        engine_url=engine_url,
        registration_key=registration_key,
        executor=executor,
        metadata={
            "tools": ["get_current_time", "calculate", "search_knowledge"],
        },
    )

    print(f"Starting agent...")
    print(f"Engine URL: {engine_url}")
    print("Model credentials will be provided by the Engine via task.model")
    print("Constructor tools: get_current_time, calculate, search_knowledge")
    if workspace_path:
        print(f"Workspace path: {workspace_path}")
        print("File tools (read_file, write_file, etc.) will be available from task.tools")
    else:
        print("Workspace not set - file tools will be disabled")
    print("Press Ctrl+C to stop")
    print()

    # Run (blocks until shutdown)
    await agent.run()


if __name__ == "__main__":
    asyncio.run(main())

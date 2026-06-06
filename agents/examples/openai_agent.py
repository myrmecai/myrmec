#!/usr/bin/env python3
"""
OpenAI Agent Example (No LangChain)

This example demonstrates how to create an agent using OpenAI directly,
without the LangChain dependency. Shows:
1. Building prompts from task.system_prompt and task.step_prompt
2. Tool calling with direct OpenAI API
3. Sending logs and progress to the Engine

Usage:
    export MYRMEC_REGISTRATION_KEY=myr_agent_xxx
    export OPENAI_API_KEY=sk-...
    python openai_agent.py
"""

import asyncio
import json
import logging
import os
from datetime import datetime
from typing import Any

from openai import AsyncOpenAI

from myrmec.agent import Agent, Task, TaskContext, TaskExecutor, TaskResult

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

logger = logging.getLogger(__name__)


# ==================== Tools ====================

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_current_time",
            "description": "Get the current date and time",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "calculate",
            "description": "Evaluate a mathematical expression",
            "parameters": {
                "type": "object",
                "properties": {
                    "expression": {
                        "type": "string",
                        "description": "A mathematical expression like '2 + 2' or 'sqrt(16)'",
                    }
                },
                "required": ["expression"],
            },
        },
    },
]


def execute_tool(name: str, args: dict[str, Any]) -> str:
    """Execute a tool and return the result."""
    import math
    
    if name == "get_current_time":
        return datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    
    elif name == "calculate":
        expression = args.get("expression", "")
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
    
    return f"Unknown tool: {name}"


# ==================== Executor ====================

class OpenAIExecutor(TaskExecutor):
    """Task executor using direct OpenAI API with tool calling."""
    
    def __init__(self, model: str = "gpt-4o-mini", max_iterations: int = 10):
        self.model = model
        self.max_iterations = max_iterations
        self.client: AsyncOpenAI | None = None
    
    async def on_startup(self) -> None:
        """Initialize OpenAI client."""
        self.client = AsyncOpenAI()
        logger.info(f"OpenAI executor initialized with model: {self.model}")
    
    async def on_shutdown(self) -> None:
        """Cleanup."""
        if self.client:
            await self.client.close()
    
    async def execute(self, task: Task, context: TaskContext) -> TaskResult:
        """Execute task using OpenAI with tool calling."""
        await context.log_info(f"Starting OpenAI execution for step: {task.step_name}")
        
        # Build system prompt from Agent Profile + Knowledge context
        system_parts = []
        
        if task.system_prompt:
            system_parts.append(task.system_prompt)
            await context.log_info(f"Using system prompt from agent profile ({len(task.system_prompt)} chars)")
        
        if task.context and task.context.knowledge:
            knowledge_section = task.context.compile_system_prompt_section()
            if knowledge_section:
                system_parts.append(f"\n# Project Context\n\n{knowledge_section}")
                await context.log_info(
                    f"Loaded {len(task.context.knowledge)} knowledge documents "
                    f"({task.context.knowledge_char_count} chars)"
                )
        
        # Build user prompt from Step Prompt + Input
        user_parts = []
        
        if task.step_prompt:
            user_parts.append(task.step_prompt)
            await context.log_info(f"Using step prompt from workflow ({len(task.step_prompt)} chars)")
        
        if task.input:
            if "prompt" in task.input:
                user_parts.append(task.input["prompt"])
            else:
                user_parts.append(f"## Input Data\n```json\n{json.dumps(task.input, indent=2)}\n```")
        
        # Build messages
        messages = []
        if system_parts:
            messages.append({"role": "system", "content": "\n\n".join(system_parts)})
        if user_parts:
            messages.append({"role": "user", "content": "\n\n".join(user_parts)})
        
        await context.log_debug(f"Built {len(messages)} messages for OpenAI")
        await context.report_progress(10, "Calling LLM...")
        
        # Agent loop with tool calling
        iteration = 0
        while iteration < self.max_iterations:
            if context.is_cancelled:
                await context.log_info("Task cancelled")
                return TaskResult(output={"cancelled": True})
            
            iteration += 1
            progress = min(90, 10 + iteration * 8)
            
            # Call OpenAI
            await context.log_debug(f"OpenAI call iteration {iteration}")
            response = await self.client.chat.completions.create(
                model=self.model,
                messages=messages,
                tools=TOOLS if iteration == 1 else None,  # Only include tools on first call
                tool_choice="auto" if iteration == 1 else None,
            )
            
            message = response.choices[0].message
            
            # Check for tool calls
            if message.tool_calls:
                await context.log_info(f"Model requested {len(message.tool_calls)} tool call(s)")
                messages.append(message.model_dump())
                
                for tool_call in message.tool_calls:
                    tool_name = tool_call.function.name
                    tool_args = json.loads(tool_call.function.arguments)
                    
                    # Track the tool call
                    async with await context.track_tool_call(tool_name, tool_args) as tracker:
                        await context.log_info(f"Executing tool: {tool_name}")
                        
                        try:
                            result = execute_tool(tool_name, tool_args)
                            tracker.set_output({"result": result})
                        except Exception as e:
                            result = f"Error: {e}"
                            tracker.set_error(str(e))
                        
                        messages.append({
                            "role": "tool",
                            "tool_call_id": tool_call.id,
                            "content": result,
                        })
                
                await context.report_progress(progress, f"Processed tool calls")
            else:
                # No tool calls - final response
                await context.log_info("Model returned final response")
                await context.report_progress(100, "Complete")
                
                return TaskResult(output={
                    "response": message.content,
                    "iterations": iteration,
                    "model": self.model,
                })
        
        # Max iterations
        await context.log_warn(f"Max iterations ({self.max_iterations}) reached")
        return TaskResult(output={
            "response": messages[-1].get("content", ""),
            "iterations": iteration,
            "maxIterationsReached": True,
        })
    
    async def on_cancel(self, task: Task, reason: str) -> None:
        """Handle cancellation."""
        logger.info(f"Task {task.task_id} cancelled: {reason}")


# ==================== Main ====================

async def main():
    engine_url = os.environ.get("MYRMEC_ENGINE_URL", "http://localhost:9090")
    registration_key = os.environ.get("MYRMEC_REGISTRATION_KEY")
    model = os.environ.get("MYRMEC_MODEL", "gpt-4o-mini")

    if not registration_key:
        print("Error: MYRMEC_REGISTRATION_KEY is required")
        exit(1)

    if not os.environ.get("OPENAI_API_KEY"):
        print("Error: OPENAI_API_KEY is required")
        exit(1)
    
    executor = OpenAIExecutor(model=model)
    
    agent = Agent(
        engine_url=engine_url,
        registration_key=registration_key,
        executor=executor,
        metadata={
            "model": model,
            "tools": ["get_current_time", "calculate"],
        },
    )

    print(f"Starting OpenAI agent...")
    print(f"Engine: {engine_url}")
    print(f"Model: {model}")
    print("Press Ctrl+C to stop\n")

    await agent.run()


if __name__ == "__main__":
    asyncio.run(main())

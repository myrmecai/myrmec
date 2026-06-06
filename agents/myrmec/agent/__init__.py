"""
Myrmec Agent SDK

Provides the core components for building agents that connect to the
Myrmec Control Plane via HTTP (registration) and WebSocket (task execution).

Quick Start:
    from myrmec.agent import Agent, TaskExecutor, Task, TaskResult, TaskContext
    
    class MyExecutor(TaskExecutor):
        async def execute(self, task: Task, context: TaskContext) -> TaskResult:
            # Your task execution logic here
            await context.log_info("Processing task...")
            return TaskResult(output={"result": "done"})
    
    agent = Agent(
        engine_url="http://localhost:8080",
        registration_key="myr_agent_...",
        executor=MyExecutor(),
    )
    await agent.run()

For LangChain integration:
    from myrmec.agent.langchain import LangChainExecutor
    
    executor = LangChainExecutor(
        model=ChatOpenAI(model="gpt-4"),
        tools=[...],
    )
    agent = Agent(..., executor=executor)
"""

from myrmec.agent.agent import Agent
from myrmec.agent.executor import TaskExecutor
from myrmec.agent.context import TaskContext
from myrmec.agent.models import LogSource, Task, TaskResult, ToolDefinition, ModelInfo
from myrmec.agent.logging_handler import AgentLoggingHandler, OutputCapture

__all__ = [
    "Agent",
    "TaskExecutor",
    "TaskContext",
    "Task",
    "TaskResult",
    "ToolDefinition",
    "ModelInfo",
    "LogSource",
    "AgentLoggingHandler",
    "OutputCapture",
]

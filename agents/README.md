# Myrmec Agent SDK

Python SDK for connecting agents to the Myrmec Control Engine.

## Installation

```bash
cd agents
pip install -e .

# With LangChain support
pip install -e ".[langchain]"
```

## Quick Start

```python
import asyncio
from myrmec.agent import Agent, Task, TaskContext, TaskExecutor, TaskResult

class MyExecutor(TaskExecutor):
    async def execute(self, task: Task, context: TaskContext) -> TaskResult:
        await context.log_info(f"Processing: {task.step_name}")
        
        # Your task logic here
        result = await do_something(task.input)
        
        await context.report_progress(100, "Done")
        return TaskResult(output={"result": result})

async def main():
    agent = Agent(
        engine_url="http://localhost:8080",
        registration_key="myr_agent_xxx",
        executor=MyExecutor(),
    )
    await agent.run()

asyncio.run(main())
```

## LangChain Integration

```python
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from myrmec.agent import Agent
from myrmec.agent.langchain import LangChainExecutor

@tool
def search(query: str) -> str:
    '''Search for information.'''
    return "search results..."

executor = LangChainExecutor(
    model=ChatOpenAI(model="gpt-4"),
    tools=[search],
    system_prompt="You are a helpful assistant.",
)

agent = Agent(
    engine_url="http://localhost:8080",
    registration_key="myr_agent_xxx",
    executor=executor,
)
```

## Running the Example

```bash
export MYRMEC_REGISTRATION_KEY=myr_agent_xxx
python examples/simple_agent.py
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MYRMEC_ENGINE_URL` | `http://localhost:8080` | Engine URL |
| `MYRMEC_REGISTRATION_KEY` | (required) | Agent registration key |

## Architecture

The SDK uses a dual-channel architecture:

- **HTTP REST**: Registration, token refresh
- **WebSocket**: Real-time task assignment, status updates, logs

```
┌─────────────────────────────────────────────────┐
│                   Agent SDK                      │
├─────────────────────────────────────────────────┤
│  Agent                                          │
│  ├── Registration (HTTP)                        │
│  ├── WebSocket Connection                       │
│  └── Task Dispatch                              │
├─────────────────────────────────────────────────┤
│  TaskExecutor (ABC)                             │
│  ├── execute(task, context) -> TaskResult       │
│  ├── on_cancel(task, reason)                    │
│  ├── on_startup()                               │
│  └── on_shutdown()                              │
├─────────────────────────────────────────────────┤
│  TaskContext                                    │
│  ├── log_info/warn/error/debug()                │
│  ├── report_progress(0-100)                     │
│  └── track_tool_call()                          │
├─────────────────────────────────────────────────┤
│  LangChainExecutor (optional)                   │
│  └── Uses LangChain for LLM-based execution     │
└─────────────────────────────────────────────────┘
```

## Structure

```
agents/
├── myrmec/
│   ├── __init__.py
│   └── agent/
│       ├── __init__.py      # Public API exports
│       ├── agent.py         # Main Agent class
│       ├── executor.py      # TaskExecutor ABC
│       ├── context.py       # TaskContext
│       ├── models.py        # Data models
│       ├── connection.py    # WebSocket client
│       ├── http_client.py   # HTTP client
│       ├── messages.py      # Protocol messages
│       └── langchain/
│           ├── __init__.py
│           └── executor.py  # LangChainExecutor
├── examples/
│   └── simple_agent.py
└── pyproject.toml
```

## Tech Stack

- **Runtime**: Python 3.11+
- **HTTP Client**: httpx
- **Validation**: Pydantic

## Features (Step 1)

- **Registration**: Register with control plane using registration key
- **Heartbeat**: Background task sends periodic heartbeats
- **Graceful shutdown**: Signal handling for clean shutdown

## Future Features

- Task polling and execution
- WebSocket log streaming
- LangGraph workflow integration

# Run sample agent
python -m myrmec.sample_agent

# Run tests
pytest
```

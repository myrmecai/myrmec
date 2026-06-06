---
description: "Use when writing Python code for Myrmec agents. Covers async patterns, typing, and SDK conventions."
applyTo: "agents/**/*.py"
---
# Agents Coding Standards (Python)

## Type Hints

- All public functions must have type hints
- Use `Optional[T]` for nullable parameters
- Use `list[str]` not `List[str]` (Python 3.11+)

```python
def process_task(task_id: str, config: Optional[dict[str, Any]] = None) -> TaskResult:
    ...
```

## Async Patterns

- Use `httpx` for HTTP calls (sync client for simplicity in SDK)
- Background tasks with `threading` (not asyncio) for heartbeat
- Use `threading.Event` for graceful shutdown signals

## Error Handling

- Catch specific exceptions, not bare `except:`
- Log errors with context before re-raising
- Use custom exceptions for domain errors

```python
try:
    response = self._client.post(url, json=payload)
    response.raise_for_status()
except httpx.HTTPStatusError as e:
    logger.error(f"Registration failed: {e.response.status_code}")
    raise RegistrationError(f"Failed to register: {e}")
```

## Logging

- Use `logging` module, configure at application entry point
- Logger per module: `logger = logging.getLogger(__name__)`
- Never print() in library code

## Naming

- Classes: `PascalCase`
- Functions/variables: `snake_case`
- Constants: `UPPER_SNAKE_CASE`
- Private: prefix with `_`

## API Endpoints

- Agent endpoints use `/api/v1/agent/` prefix (not `/api/v1/agents/`)
- Registration: `POST /api/v1/agent/auth/register`
- Token refresh: `POST /api/v1/agent/auth/refresh`
- Heartbeat: `POST /api/v1/agent/heartbeat`
- Agent info: `GET /api/v1/agent/me`

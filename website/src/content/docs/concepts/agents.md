---
title: Agents
description: Runtime workers that execute tasks.
---

Agents are execution workers that connect to the control plane and process tasks.

## Lifecycle

1. Register via key-based auth
2. Receive JWT tokens
3. Connect to WebSocket stream
4. Accept task assignments
5. Execute with tools and model context
6. Stream progress and completion events

## Key behaviors

- Heartbeat and reconnect handling
- Tool invocation loop
- Structured result reporting
- Failure signaling with error metadata

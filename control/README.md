# Control Plane

The control plane manages agent registration, project configuration, workflow orchestration, and task distribution.

## Structure

```
control/
├── engine/    # Backend API & Workflow Engine
└── ui/        # Web Interface
```

## Components

### Engine (`engine/`)
FastAPI-based backend providing:
- REST API for CRUD operations
- WebSocket server for log streaming
- Workflow engine for task orchestration
- PostgreSQL for state management

### UI (`ui/`)
React-based web interface providing:
- Admin console for agent & project management
- Workflow builder (visual editor)
- Request submission portal
- Real-time monitoring & log viewer

## Communication

```
┌──────────┐     HTTP/WS      ┌──────────┐
│  Agents  │◄────────────────►│  Engine  │
└──────────┘                  └────┬─────┘
                                   │
                                   │ REST
                                   ▼
                              ┌──────────┐
                              │    UI    │
                              └──────────┘
```

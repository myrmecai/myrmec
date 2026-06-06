# Control Plane Engine

Spring Boot-based backend for the Myrmec control plane.

## Maven Coordinates

```xml
<groupId>ai.myrmec</groupId>
<artifactId>control-engine</artifactId>
```

**Base Package:** `ai.myrmec.engine`

## Features

- **Agent Registration**: Key-based authentication, JWT tokens
- **Project Management**: CRUD for projects and workflows
- **Task Orchestration**: Atomic task claiming, workflow state machine
- **Log Streaming**: WebSocket with MessagePack binary format
- **Health Monitoring**: Agent heartbeats, status tracking

## Tech Stack

- **Framework**: Spring Boot 3.x (Java 21)
- **Build**: Maven
- **Database**: PostgreSQL 15 (production), H2 (E2E tests)
- **Schema Management**: Liquibase (database-agnostic scripts)
- **Auth**: Spring Security + JWT
- **WebSocket**: Spring WebSocket + MessagePack

## API Overview

```
# Agent Operations
POST   /api/v1/agents/register
GET    /api/v1/agents/:id/tasks/next
POST   /api/v1/tasks/:id/claim
POST   /api/v1/tasks/:id/complete
WS     /api/v1/agents/:id/stream

# Admin Operations
POST   /api/v1/admin/registration-keys
GET    /api/v1/agents
POST   /api/v1/projects
POST   /api/v1/workflows
POST   /api/v1/requests
```

## Setup

*Coming soon*

## Development

```bash
# Build
mvn clean install

# Run development server
mvn spring-boot:run

# Run tests
mvn test
```

## Non-Functional Requirements

### Liquibase Guidelines

Liquibase changesets **must be database-agnostic** to support:
- **PostgreSQL 15** in production
- **H2 (embedded)** for E2E integration tests

Rules:
- Use ANSI SQL where possible
- Avoid PostgreSQL-specific syntax (e.g., `SERIAL` → use `BIGINT` with identity)
- No vendor-specific functions (e.g., `now()` → use Liquibase `${now}` property)
- Test migrations with both PostgreSQL and H2 profiles

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `MYRMEC_ENCRYPTION_KEY` | Base64-encoded 32-byte AES-256 key for credential encryption/decryption | Yes |
| `DATABASE_URL` | PostgreSQL connection string | Yes |
| `JWT_SECRET` | Secret for signing JWT tokens | Yes |

### Credential Management

LLM API keys are stored **encrypted** in the `model_info` table using AES-256-GCM.

- **Encryption key**: Injected via `MYRMEC_ENCRYPTION_KEY` environment variable
- **Agent retrieval**: Agents call `GET /api/v1/models/config` to get decrypted config
- **Never logged**: Decrypted keys are never written to logs

### Project Authentication

Projects have a secret key for agent access control:

- **Generation**: UI generates `myr_proj_{random_32_bytes}` at project creation
- **Storage**: Encrypted in `projects.secret_key_encrypted`
- **Agent usage**: Include in header `X-Project-Key: myr_proj_xxx`
- **Access control**: Agents can only use models in `project_models` for their project
- **Parameter restrictions**: `max_temperature`, `max_tokens_limit` enforced per project

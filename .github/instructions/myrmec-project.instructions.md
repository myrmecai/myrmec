---
description: "Use when working on Myrmec features, architecture decisions, or cross-component tasks. Covers project conventions and design patterns."
applyTo: "**"
---
# Myrmec Project Instructions

## Architecture

- **Control Plane Engine**: Spring Boot 3.x, Java 21, PostgreSQL
- **Control Plane UI**: React Native, TypeScript
- **Agents**: Python 3.11+, httpx, Pydantic

## Database

- Liquibase for all schema changes (XML format with preConditions)
- JSONB for flexible metadata columns
- Timestamps as `TIMESTAMP WITH TIME ZONE` (mapped to `Instant` in Java)

### Primary Key Strategy

| Table Category | PK Type | Example Tables |
|----------------|---------|----------------|
| **Reference/Config data** | `code` (varchar, natural key) | models, model_providers |
| **Operational data** | `id` (UUID) | agents, agent_profiles, agent_instances, users, tasks |

**Rationale:**
- Reference data: Human-readable codes (e.g., `openai`, `gpt-4-turbo`) are stable, meaningful, and used in config files
- Operational data: UUIDs prevent enumeration, enable distributed generation, and avoid namespace conflicts

**Rules:**
- NO redundant `id` + `code` columns — pick one pattern per table
- Operational tables use `name` for display, `id` for references
- Reference tables use `code` for both identification and display

**When to use `code` as PK:**
- Entity has well-known, industry-standard identifiers (openai, anthropic, gpt-4-turbo)
- Values are stable and rarely renamed
- Used in configuration files or external integrations

**When to use `id` (UUID) as PK:**
- User-created entities (projects, agents, tasks)
- Entities that may be renamed
- Operational/transactional data

## Authentication

### Principal Types
- **AGENT**: Machine agents, authenticate via registration key → JWT
- **USER**: Human operators, authenticate via login → JWT

### Agent Auth
- Registration keys: `myr_agent_{base64}` for agent registration
- Project keys: `myr_proj_{base64}` for project access
- JWT tokens with `principal: "AGENT"` claim

### User Auth
- Internal: email/password with BCrypt (10 rounds)
- OIDC: broker mode (users must pre-exist, no auto-create)
- JWT tokens with `principal: "USER"` and `roles` claim
- Password policy: 8-50 chars, letter + digit + special char

### User Roles

Roles are organized along two independent axes — **administrative** (platform vs. governance) and
**data-access** (PROJECT_OWNER → EDITOR → VIEWER) — plus a few orthogonal special-purpose roles.
Every role is assigned at one **scope**: `SYSTEM`, `GROUP`, or `PROJECT`.

| Role | Axis | Description |
|------|------|-------------|
| `PLATFORM_ADMIN` | platform | Models, providers, agents, auth providers, global secrets, users. **Does not** imply data access. SYSTEM scope only. |
| `ORG_ADMIN` | governance | Manages groups, role grants, policy, audit. Implies `AUDITOR` (and therefore `VIEWER`). SYSTEM scope only. |
| `PROJECT_OWNER` | data-access | Owns a project: delete/transfer, manage members, set project quota. Implies `EDITOR`. PROJECT scope only. |
| `EDITOR` | data-access | Authoring rights (workflows, secrets, knowledge). Implies `VIEWER`. Any scope. |
| `VIEWER` | data-access | Read-only data access in scope. Any scope. |
| `BUDGET_OWNER` | quota | Manages quotas/budgets in scope. No data access. SYSTEM, GROUP, or PROJECT. |
| `APPROVER` | HITL | Approves runs / human-in-the-loop steps. Implies `VIEWER`. SYSTEM, GROUP, or PROJECT. |
| `AUDITOR` | observability | Read-only across data + audit + spend in scope. SYSTEM, GROUP, or PROJECT. |

Implicit-role expansion (applied by `UserPrincipal` and the access evaluators):

- `PROJECT_OWNER` ⇒ `EDITOR` ⇒ `VIEWER`
- `EDITOR` ⇒ `VIEWER`
- `APPROVER` ⇒ `VIEWER`
- `AUDITOR` ⇒ `VIEWER` (auditor is read-only across data + audit + spend)
- `ORG_ADMIN` ⇒ `AUDITOR` ⇒ `VIEWER`
- `PLATFORM_ADMIN` is intentionally isolated (separation of duties).

Group-scoped roles cascade down to descendant groups and to projects in those groups (ancestor walk
in `ProjectAccessEvaluator` / `GroupAccessEvaluator`).

### JWT Roles Format

Role claims are scope-prefixed strings:

- System-wide: `"sys:PLATFORM_ADMIN"`, `"sys:ORG_ADMIN"`, `"sys:EDITOR"`, `"sys:VIEWER"`, `"sys:AUDITOR"`, `"sys:APPROVER"`, `"sys:BUDGET_OWNER"`
- Group-scoped: `"grp:<groupId>:EDITOR"`, `"grp:<groupId>:VIEWER"`, `"grp:<groupId>:BUDGET_OWNER"`, etc.
- Project-scoped: `"proj:<projectId>:PROJECT_OWNER"`, `"proj:<projectId>:EDITOR"`, `"proj:<projectId>:VIEWER"`, …

### Security
- AES-256-GCM for credential encryption in DB
- Access tokens: 15 minutes TTL
- Refresh tokens: 7 days TTL

## API Design

### Path Authorization
| Path | Required Auth |
|------|---------------|
| `/api/v1/auth/**` | Public (user login, refresh) |
| `/api/v1/agent/auth/**` | Public (agent register, refresh) |
| `/api/v1/agent/**` | Agent JWT (`ROLE_AGENT`) |
| `/api/v1/admin/**` | User JWT — `ROLE_PLATFORM_ADMIN` or `ROLE_ORG_ADMIN` (individual controllers tighten further) |
| `/api/v1/**` | Any authenticated principal (per-resource `@PreAuthorize` gates evaluate scope) |

### Conventions
- REST endpoints under `/api/v1/`
- Request bodies: camelCase JSON
- Response bodies: camelCase JSON
- Headers: `X-Registration-Key`, `X-Project-Key`, `Authorization: Bearer <jwt>`

## Documentation

- Update `docs/*.md` when changing architecture
- Update `docs/ERD.mmd` when changing schema
- Update `STEP-*.md` when adding implementation phases

## Testing

- H2 in-memory for Spring Boot tests
- pytest for Python agent tests
- Integration tests via `scripts/test-step*.ps1`

## Error Response Format

All API errors follow a consistent JSON format for cross-platform compatibility and localization.

### Standard Error Response

```json
{
  "errorCode": "ERROR_CODE",
  "message": "Human-readable message in English",
  "details": { ... }
}
```

### Error Codes

| Error Code | HTTP Status | Description |
|------------|-------------|-------------|
| `VALIDATION_ERROR` | 400 | Input validation failed |
| `RESOURCE_NOT_FOUND` | 404 | Requested resource doesn't exist |
| `RESOURCE_IN_USE` | 409 | Resource cannot be deleted (referenced) |
| `DUPLICATE_CODE` | 409 | Unique constraint violation |
| `UNAUTHORIZED` | 401 | Missing or invalid credentials |
| `FORBIDDEN` | 403 | Insufficient permissions |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

### Validation Error Details

```json
{
  "errorCode": "VALIDATION_ERROR",
  "message": "One or more validation errors occurred.",
  "details": [
    {
      "field": "name",
      "errorCode": "REQUIRED",
      "message": "Name is required."
    },
    {
      "field": "metadata.version",
      "errorCode": "INVALID_FORMAT",
      "message": "Version must be in format x.y.z."
    }
  ]
}
```

### Common Field Error Codes

| Field Error Code | Description |
|------------------|-------------|
| `REQUIRED` | Field is required but missing |
| `INVALID_FORMAT` | Field doesn't match expected pattern |
| `TOO_SHORT` | Field is below minimum length |
| `TOO_LONG` | Field exceeds maximum length |
| `INVALID_VALUE` | Field value is not in allowed set |

### Resource Not Found Details

```json
{
  "errorCode": "RESOURCE_NOT_FOUND",
  "message": "The requested resource was not found.",
  "details": {
    "resourceType": "Project",
    "identifier": "my-project-code"
  }
}
```

### Resource In Use Details

```json
{
  "errorCode": "RESOURCE_IN_USE",
  "message": "The resource cannot be deleted because it is still in use.",
  "details": [
    {
      "resourceType": "Workflow",
      "blocking": true,
      "count": 2
    }
  ]
}
```

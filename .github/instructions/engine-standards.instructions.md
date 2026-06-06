---
description: "Use when writing Java/Spring Boot code in the control engine. Covers service patterns, entity design, and Spring conventions."
applyTo: "control/engine/**/*.java"
---
# Engine Coding Standards (Java/Spring Boot)

## Package Structure

Organize by **functional components** (features), not technical layers.

```
ai.myrmec.engine/
├── _system/              # System-level technical components
│   ├── config/           # Spring configuration
│   ├── security/         # JWT, filters, auth
│   ├── exception/        # Global exception handlers
│   ├── bootstrap/        # Startup initializers (AdminUserBootstrap)
│   └── common/           # Shared utilities
├── agent/                # Agent feature
│   ├── Agent.java        # Entity
│   ├── AgentRepository.java
│   ├── AgentService.java
│   └── AgentController.java
├── registration/         # Registration key feature
│   ├── RegistrationKey.java
│   ├── RegistrationKeyRepository.java
│   └── RegistrationKeyService.java
├── user/                 # User authentication & management
│   ├── User.java         # Entity with AuthProvider enum
│   ├── UserRole.java     # Entity with Role enum
│   ├── UserRepository.java
│   ├── UserRoleRepository.java
│   ├── UserService.java
│   ├── UserAuthService.java
│   ├── UserAuthController.java      # /api/v1/auth/*
│   ├── UserManagementController.java # /api/v1/admin/users/*
│   ├── UserPrincipal.java
│   ├── PasswordValidator.java
│   └── dto/              # LoginRequest, UserResponse, etc.
└── project/              # Project feature
    ├── Project.java
    ├── ProjectRepository.java
    ├── ProjectService.java
    └── controller/       # Sub-package only when >1 class
        ├── ProjectController.java
        └── ProjectAdminController.java
```

**Rules:**
- `_system/` prefix for system-level components (security, config, exception, common, filters)
- Functional packages contain entity, repository, service, controller together
- Create sub-packages (e.g., `controller/`) only when there are multiple classes of that type
- DTOs can live in the functional package or a `dto/` sub-package if there are many

## Service Layer

- Use `@Transactional` on public service methods that modify data
- Return domain entities from services, convert to DTOs in controllers
- Throw domain-specific exceptions (e.g., `InvalidRegistrationKeyException`), not generic ones
- Use constructor injection via `@RequiredArgsConstructor`

## Entity Design

- Use `UUID` for primary keys with `@GeneratedValue(strategy = GenerationType.UUID)`
- Add `@PrePersist` for default timestamps, don't rely on DB defaults in Java
- Use `Instant` for timestamps, never `Date` or `LocalDateTime`
- JSON columns: use `@Convert` with custom converters for database-agnostic JSON storage:
  - `JsonMapConverter` for `Map<String, Object>`
  - `JsonListConverter` for `List<String>`
  - `JsonListMapConverter` for `List<Map<String, Object>>`

## Logging

- Log at method boundaries: entry (DEBUG), success (INFO), failure (WARN/ERROR)
- Never log sensitive data (keys, tokens, credentials)
- Use structured logging: `log.info("Action completed: {} (id: {})", name, id)`

## Naming

- Services: `*Service` (not `*ServiceImpl`)
- Repositories: `*Repository`
- Controllers: `*Controller`
- DTOs: `*Request`, `*Response`

## Code Style

- Prefer early returns over nested if/else
- Use Optional methods: `orElseThrow()`, `map()`, `filter()`
- Constants at class level with `private static final`

## Database and JPA
- Use Liquibase for all schema changes, keep entities in sync with changelogs
- Use `@Column` for all fields, even if defaults are sufficient, for clarity
- Avoid using JPA relationships. Store foreign keys and load related entities manually in service layer when needed. This gives more control and avoids N+1 query issues.
- Use QueryDSL or custom queries for complex data access patterns instead of relying on JPA method naming conventions.
- JSON columns should use TEXT type in database and custom JPA converters (`JsonMapConverter`, `JsonListConverter`, `JsonListMapConverter`) for serialization. This ensures compatibility with both PostgreSQL and H2. 

## Liquibase Migrations

- Use XML format for all changesets, except for the main `db.changelog-master.yaml` which can be YAML
- Always add `preConditions` for idempotency:
  ```yaml
  preConditions:
    - onFail: MARK_RAN
    - not:
        tableExists:
          tableName: your_table
  ```
- Use `gen_random_uuid()` for UUID defaults (PostgreSQL)
- Index names: `idx_{table}_{column}`
- Foreign key names: `fk_{source_table}_{target_table}`
- Avoid database-specific syntax to maintain compatibility with H2 for tests
- Changelog files should be named with date prefix for ordering: `YYYYMMDD`-NN-description.xml
## Error Handling

See project instructions for the standard error response format. This section covers implementation.

### Global Exception Handling

- Use `@ControllerAdvice` for global exception handling
- Return consistent error response format (see project instructions)
- All user-facing messages in English with error codes for localization

### Security Logging

- Technical exceptions: log with stack trace, return generic message to client
- Security exceptions: return appropriate HTTP status without revealing resource existence
- Log sensitive data with masking: `myr_agent_************abc123`

### Exception Classes

```java
// BadRequestException - 400
throw new BadRequestException("Invalid project code format");

// ResourceNotFoundException - 404  
throw ResourceNotFoundException.of("Project", "code", projectCode);

// ResourceInUseException - 409
throw new ResourceInUseException("Project", projectCode, blockingResources);
```

### Validation Error Builder

```java
// For field-level validation errors
ValidationErrorBuilder.create()
    .addError("name", "REQUIRED", "Name is required.")
    .addError("code", "INVALID_FORMAT", "Code must be alphanumeric.")
    .throwIfErrors();
```
## REST API Design

- Use RESTful conventions: nouns for resources, HTTP methods for actions
- Use camelCase for JSON fields in requests/responses
- Use appropriate HTTP status codes (per error response format in project instructions)
- Configure Spring Doc for API documentation with descriptions and examples
- Resource update: PATCH for partial updates, PUT for full replacement


# Contributing to Myrmec

Thanks for your interest in Myrmec. This is a solo-maintained project today — response times may
vary, but issues and PRs are welcome.

## Project layout

```
myrmec/
├── control/
│   ├── engine/   # Spring Boot 3.x (Java 21) control-plane API — Maven multi-module
│   │   ├── engine-core/   # main application
│   │   └── engine-spi/    # pluggable provider interfaces (Community defaults + Enterprise overrides)
│   └── ui/       # Control Plane UI — Vite + TypeScript
├── agents/       # @myrmec/agent SDK — TypeScript / Node.js, built on LangChain.js
├── docker-compose.yml
└── docs/
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the system shape and core concepts.

## Building & running locally

**Full stack (Postgres + Engine + UI):**

```
docker-compose up
```

**Engine only** (from `control/engine/`):

```
mvn clean install
mvn -pl engine-core spring-boot:run
```

Tests run against an in-memory H2 database by default (`spring.profiles.active=e2e` for the
e2e test profile) — no external Postgres needed for the test suite.

**UI only** (from `control/ui/`):

```
npm install
npm run dev
```

**Agent SDK** (from `agents/`):

```
npm install
npm test        # vitest
npm run build   # tsc
npm run lint
```

## Coding conventions

Conventions live next to the code they govern, not duplicated here:

- Engine (Java/Spring Boot): `.github/instructions/engine-standards.instructions.md`
- UI (React/TypeScript): `.github/instructions/ui-standards.instructions.md`
- Agent SDK (TypeScript): `.github/instructions/agents-standards.instructions.md`
- Cross-cutting project conventions: `.github/instructions/myrmec-project.instructions.md`

## Reporting issues

Use GitHub Issues. For security issues, see [SECURITY.md](SECURITY.md) instead of filing a
public issue.

## Pull requests

- Keep PRs focused — one change per PR.
- Add/update tests for behavior changes.
- Liquibase changesets are database-agnostic (must run against both H2 and Postgres).

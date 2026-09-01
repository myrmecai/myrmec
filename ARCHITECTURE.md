# Myrmec Architecture

> Public, trimmed overview — enough to orient a contributor. Deeper internal design (roadmap,
> competitive positioning, full governance/inheritance mechanics, licensing) lives in the
> private working repo, not here.

## Vision

A governed runtime where AI agents read your organization's knowledge and act on it — in a
sandbox, under audit — instead of ad-hoc, ungoverned prompting.

## System Components

- **Control Plane Engine** (`control/engine/`) — Spring Boot 3.x (Java 21) backed by
  PostgreSQL. Stateless, horizontally scalable. Owns configuration, workflow/assistant
  definitions, executions, conversations, and audit records; exposes the REST API consumed by
  the UI and by Agents.
- **Control Plane UI** (`control/ui/`) — TypeScript web application.
- **Agents** (`agents/`) — TypeScript / Node.js processes built on the `@myrmec/agent` SDK
  (LangChain.js). Stateless, horizontally scalable; teams build their own agent images with
  the SDK.

## Communication

Two transports, chosen per data direction, not per feature:

- **Agent ↔ Engine**: HTTP REST (registration, token refresh) + WebSocket (task assignment,
  progress, logs, tool calls, heartbeat) — genuinely bidirectional and multiplexed, so it stays
  on WebSocket.
- **Client ↔ Engine** (Web UI, IDE plugins, external API): HTTP REST for client-originated
  actions (send a message, cancel) + Server-Sent Events (SSE) for the server→client reply
  stream. The client uplink is always a discrete REST call; only the reply stream needs a
  held-open channel, and SSE's native `Last-Event-ID` resume model maps directly onto sequence-
  based replay.

## Core Concepts

- **Workflow** — a versioned, multi-step definition; triggering one creates an **Execution**
  (a `Run`).
- **Assistant** — a versioned, conversational definition bound to an Agent Profile and a set of
  Knowledge Bases; starting a chat creates a **Conversation Session**.
- **Agent Profile** — the behavior contract: model, required tools, system prompt, sandbox
  image. Bound to an Agent at *reserve time* (task dispatch), not at registration.
- **Agent Host** — the execution environment (container, bare-metal/VM, or IDE plugin) that
  registers with the Control Plane and hosts one or more Agents. Declares its provisions
  (tools/runtime); an Agent Profile's requirements are matched against a host's provisions at
  reserve time.
- **Agent** — a single-task worker running inside an Agent Host. Works on one task at a time,
  has no persistent memory between tasks — the Control Plane assembles and ships full context
  at dispatch time.
- **Service Type** — the extensible capability axis (`WORKFLOW`, `CONVERSATIONAL`, and future
  types like `VOICE_SESSION`). Every Service Type follows the same
  Definition → Instance → Atom shape (e.g. Workflow → Execution → Step, or
  Assistant → Conversation Session → Message).
- **Governance Profile** — controls what's permitted at a given scope (instruction sources,
  knowledge provider types, budget enforcement, etc.). Projects may only select the same or a
  stricter profile than their org — never looser.
- **Versioned Entity** — any entity whose configuration affects runtime behavior (Agent
  Profile, Assistant, Instruction Asset, Agent Host, Connection Config) splits into a stable
  parent row and an immutable-once-published version row; running instances pin the version
  they started against.

## Roles

Role names follow a canonical model with two axes — administrative (`PLATFORM_ADMIN`,
`ORG_ADMIN`) and data-access (`PROJECT_OWNER` → `EDITOR` → `VIEWER`) — plus special-purpose
roles (`BUDGET_OWNER`, `APPROVER`, `AUDITOR`). Roles are assigned at `SYSTEM`, `GROUP`, or
`PROJECT` scope.

## Authentication

Two principal types: `USER` (login → JWT) and `AGENT` (registration key → JWT). Agent traffic
is a distinct principal so a stolen developer token can't impersonate an agent, or vice versa.

## Tech stack

| Layer | Technology |
|-------|------------|
| Control Plane Engine | Spring Boot 3.x (Java 21), Maven, PostgreSQL, Liquibase |
| Control Plane UI | TypeScript, Vite |
| Agents | Node.js / TypeScript, `@myrmec/agent` SDK (LangChain.js) |
| Auth | Spring Security + JWT |
| Containerization | Docker / Docker Compose |

See [CONTRIBUTING.md](CONTRIBUTING.md) for build/run/test instructions.

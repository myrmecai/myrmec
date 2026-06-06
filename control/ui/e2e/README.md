# Myrmec Control UI — End-to-End Tests (Phase 4)

This directory hosts the Playwright test harness that exercises the React UI
against a real Spring Boot engine. It is the **Tier 2** layer of the four-tier
test pyramid (see `/memories/session/plan.md`).

## Architecture

```
┌─ playwright.config.ts ─ webServer ─┐
│  1. mvn -pl engine-core spring-boot:run (port 9090, e2e profile, H2)
│  2. npm run dev (Vite, port 3000)
└────────────────────────────────────┘
         ↓ both ready
┌─ e2e/specs/*.spec.ts ──────────────┐
│  import { test, expect } from '../fixtures'
│  • `api`        — unauthenticated REST client (arrange-via-API)
│  • `adminPage`  — Page pre-authenticated as admin@e2e-test.local
└────────────────────────────────────┘
```

Single engine boot per `npm run e2e` invocation — biggest cost saving in the
suite. Tests run serially because they share the same H2 database.

## Quick start

```pwsh
# from control/ui
npm install
npx playwright install chromium    # ~150 MB, one-time
npm run e2e
```

Or run from the repo root with the orchestrator script:

```pwsh
.\scripts\e2e-all.ps1
```

## Adding a spec

1. Drop a file under `e2e/specs/*.spec.ts`.
2. Import the extended fixture: `import { test, expect } from '../fixtures'`.
3. Use `api` for data setup and `adminPage` for the actual assertion.
4. Prefer REST calls (`api.request(...)`) over UI form-fills for arrange steps
   — see the Phase 4 token-cost discipline rules in `plan.md`.

## Tier-2 vs Tier-3

This directory holds **Tier 2** specs (deterministic, run on every PR). Tier 3
specs (real-model smoke against Ollama) live in `e2e-real/` and are introduced
in a later sub-phase.

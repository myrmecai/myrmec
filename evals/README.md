# Prompt evals (Tier 4)

Out-of-band prompt-quality regression suite using [Promptfoo](https://promptfoo.dev/).
**Not** wired into CI gates — purely diagnostic. Designed to surface drift in prompt
templates, model behaviour, and answer quality before customers notice.

See the master plan `/memories/session/plan.md` §"Tier 4 — Prompt evals" for context.

## Scope (Phase 4c scaffold only)

This directory currently contains:

- `promptfooconfig.yaml` — minimal example with **one** RAG-style assertion case
  using Ollama (`llama3.1:8b`) as the provider. No real prompts wired up yet;
  the prompt template is a placeholder that exercises the harness.
- `prompts/` — empty directory; flagship prompt templates land here in Phase 10.
- `cases/` — empty directory; canonical Q&A pairs land here in Phase 10.

The full Promptfoo eval suite ships in **Phase 10** alongside the rest of the
governance work. Until then, this scaffold proves the toolchain works and
gives Phase 10 a place to land changes without re-deciding the layout.

## Running locally

```pwsh
# Prerequisite: Ollama running locally with llama3.1:8b pulled.
# winget install Ollama.Ollama
# ollama pull llama3.1:8b

cd evals
npx promptfoo@latest eval
npx promptfoo@latest view  # opens HTML report
```

Set `OPENAI_API_KEY` or `ANTHROPIC_API_KEY` to enable judge-model grading.
Without them, only `contains` / regex assertions run.

## Why this isn't in the test pyramid

Prompt evals are a different kind of feedback loop:

- Tests catch **regressions in behaviour you specified**.
- Evals catch **regressions in behaviour you hoped for** (helpfulness,
  factuality, citation discipline, tone).

They run on a slower cadence (weekly or on prompt-template changes), they
can be flaky for legitimate reasons (model nondeterminism), and they often
need a human in the loop to triage failures. Hence: separate directory,
separate runbook, no CI gate.

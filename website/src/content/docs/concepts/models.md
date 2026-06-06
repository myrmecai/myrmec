---
title: Models
description: Provider and model configuration strategy.
---

Myrmec separates model providers from model definitions.

## Provider examples

- OpenAI
- Anthropic
- Azure OpenAI
- Ollama
- Mistral

## Design goals

- Provider-agnostic orchestration
- Secure API key handling
- Project-level model assignment
- Flexible inference settings per model

This lets teams switch or blend providers without redesigning workflow logic.

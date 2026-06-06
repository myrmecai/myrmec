---
title: Tools
description: Controlled capabilities agents can invoke.
---

Tools define what an agent is allowed to do at runtime.

## Why tools matter

They prevent unbounded execution and create explicit capability boundaries.

## Common tool groups

- File operations
- Git operations
- Database access
- Infrastructure and cloud CLIs
- External service integrations

## Assignment model

Tools are attached to agent profiles. A task can only invoke tools available to the assigned profile.

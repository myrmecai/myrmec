---
title: Workflows
description: Ordered step execution for request handling.
---

Workflows are ordered sets of steps used to process a request.

## Workflow model

- Request triggers execution
- Each step produces outputs for subsequent steps
- Steps map to agent profiles
- Step outcomes determine continuation or correction path

## Typical pattern

- Generate
- Review
- Finalize

This pattern ensures changes are created, validated, and integrated in a repeatable way.

---
title: Introduction
description: What Myrmec is and why teams use it.
---

Myrmec is a distributed agent orchestration platform that helps teams run structured AI workflows using specialized agents.

## What problem it solves

Teams often run AI automation in disconnected scripts and ad-hoc bots. That creates operational blind spots:

- No shared control plane for workflows
- Weak visibility into execution state and failures
- Inconsistent model and tool governance
- No reusable context standards across teams

Myrmec addresses this by combining workflow orchestration, real-time task dispatch, model-provider flexibility, and knowledge-driven context injection.

## Core components

- Control Plane Engine: Spring Boot backend for APIs, orchestration, and WebSocket events
- Control Plane UI: operational surface for administrators and teams
- Agents: Python workers that register, claim tasks, execute tools, and report progress

## Platform principles

- Provider-agnostic model support
- Explicit tool governance via agent profiles
- Real-time visibility with durable execution state
- Security through principal separation and RBAC

---
title: Quickstart
description: Run your first workflow execution in minutes.
---

## 1. Start engine in e2e mode

```powershell
cd control/engine
$env:JAVA_HOME = "C:\jdk\jdk-21.0.6"
mvn spring-boot:run -D"spring-boot.run.profiles=e2e"
```

## 2. Seed test data

```powershell
cd ../../
./scripts/setup-e2e-data.ps1
```

This seeds:

- Tool catalog
- Model providers and models
- Addressbook project
- Feature Implementation workflow

## 3. Start the agent runtime

```powershell
./scripts/start-agent.ps1
```

The agent registers with the engine, connects over WebSocket, and starts claiming assigned tasks.

## 4. Observe execution

Monitor workflow events in logs and API responses. The execution will move through workflow steps and emit task-level progress updates.

## 5. Iterate

Edit workflow definitions, model defaults, and tool assignments to tune behavior for your use case.

---
title: Installation
description: Set up Myrmec locally for development and testing.
---

## Prerequisites

- Java 21+
- Maven 3.9+
- Python 3.11+
- PostgreSQL 15+ (or use the e2e profile with in-memory DB)

## Clone the repository

```bash
git clone <your-myrmec-repo-url>
cd myrmec
```

## Start the engine (e2e profile)

```powershell
cd control/engine
$env:JAVA_HOME = "C:\jdk\jdk-21.0.6"
mvn spring-boot:run -D"spring-boot.run.profiles=e2e"
```

## Seed address book test data

```powershell
cd ../../
./scripts/setup-e2e-data.ps1
```

## Start an agent

```powershell
./scripts/start-agent.ps1
```

## Verify

- Engine: http://localhost:9090/swagger-ui.html
- Default e2e admin: admin@e2e-test.local

"""
Pytest fixtures for agent E2E integration tests.

These tests assume the control engine is already running with the ``e2e``
profile on ``http://localhost:9090`` and the bootstrapped admin user
``admin@e2e-test.local`` / ``E2eTest@123!`` available.

Start the engine before running pytest::

    cd control/engine/engine-core
    mvn spring-boot:run "-Dspring-boot.run.profiles=e2e"

Or use ``scripts/e2e-all.ps1`` which orchestrates the same flow as the
Playwright UI suite.
"""

from __future__ import annotations

import os
import socket
import time
import uuid
from collections.abc import Iterator
from dataclasses import dataclass

import httpx
import pytest

ENGINE_URL = os.environ.get("MYRMEC_E2E_ENGINE_URL", "http://localhost:9090")
ADMIN_EMAIL = os.environ.get("MYRMEC_E2E_ADMIN_EMAIL", "admin@e2e-test.local")
ADMIN_PASSWORD = os.environ.get("MYRMEC_E2E_ADMIN_PASSWORD", "E2eTest@123!")


@dataclass(frozen=True)
class AgentFixture:
    """Material returned by the session-scoped agent fixture."""

    engine_url: str
    agent_id: str
    profile_id: str
    registration_key: str


def _wait_for_engine(timeout_seconds: float = 30.0) -> None:
    """Block until ``/actuator/health`` reports UP or the timeout elapses."""
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            resp = httpx.get(f"{ENGINE_URL}/actuator/health", timeout=2.0)
            if resp.status_code == 200 and resp.json().get("status") == "UP":
                return
        except Exception as exc:  # noqa: BLE001 - we genuinely want to retry on any failure
            last_error = exc
        time.sleep(0.5)
    raise RuntimeError(
        f"Engine at {ENGINE_URL} not reachable within {timeout_seconds}s. "
        f"Last error: {last_error}"
    )


def _login_admin() -> str:
    """Authenticate as the bootstrapped admin and return the access token."""
    resp = httpx.post(
        f"{ENGINE_URL}/api/v1/auth/login",
        json={"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD},
        timeout=10.0,
    )
    resp.raise_for_status()
    return resp.json()["accessToken"]


@pytest.fixture(scope="session")
def admin_token() -> str:
    """Session-scoped admin JWT — single login per test session."""
    _wait_for_engine()
    return _login_admin()


@pytest.fixture(scope="session")
def agent_fixture(admin_token: str) -> Iterator[AgentFixture]:
    """
    Create a fresh agent profile + agent for the test session and yield the
    registration key. Cleanup is best-effort because the engine reuses the
    in-memory H2 database across the whole session lifecycle and the next
    ``e2e`` boot will start from scratch anyway.
    """
    headers = {"Authorization": f"Bearer {admin_token}"}
    suffix = uuid.uuid4().hex[:8]

    profile_payload = {
        "name": f"e2e-agent-profile-{suffix}",
        "description": "Auto-created by agent E2E integration tests",
        "capabilities": ["python:3.11"],
        "supportedTools": [],
        "toolCodes": [],
        "systemPrompt": "You are an E2E test agent.",
        # ``defaultModel`` is a FK into ``models.code``. The ``e2e`` profile
        # seeds ``github-gpt-4o`` — adapt this here if that ever changes.
        "defaultModel": "github-gpt-4o",
    }
    profile_resp = httpx.post(
        f"{ENGINE_URL}/api/v1/admin/agent-profiles",
        json=profile_payload,
        headers=headers,
        timeout=10.0,
    )
    profile_resp.raise_for_status()
    profile_id = profile_resp.json()["id"]

    agent_payload = {
        "name": f"e2e-agent-{suffix}",
        "description": "Auto-created by agent E2E integration tests",
        "profileId": profile_id,
        "maxInstances": 1,
    }
    agent_resp = httpx.post(
        f"{ENGINE_URL}/api/v1/admin/agents",
        json=agent_payload,
        headers=headers,
        timeout=10.0,
    )
    agent_resp.raise_for_status()
    body = agent_resp.json()
    registration_key = body["registrationKey"]
    agent_id = body["agent"]["id"]

    yield AgentFixture(
        engine_url=ENGINE_URL,
        agent_id=agent_id,
        profile_id=profile_id,
        registration_key=registration_key,
    )

    # Best-effort teardown — failures are logged via pytest's terminal but
    # never fail the session because the in-memory DB resets on engine reboot.
    httpx.delete(
        f"{ENGINE_URL}/api/v1/admin/agents/{agent_id}",
        headers=headers,
        timeout=10.0,
    )


@pytest.fixture(scope="session")
def hostname() -> str:
    return socket.gethostname()

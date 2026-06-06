"""
Tier-1 agent integration tests — register + refresh through the real SDK
HTTP client against a live control engine.

These intentionally stop short of the WebSocket task-dispatch loop; that
flow is owned by the Tier-2 Playwright specs (which spawn an agent
subprocess and assert the end-to-end UI signal). Here we just prove the
SDK's HTTP envelope round-trips correctly so a Playwright failure can be
isolated from an HTTP-layer regression.

Run with::

    cd agents
    pytest e2e/test_register_lifecycle.py -v

KNOWN BUG (surfaced 2026-06-06, Phase 4b): the SDK's
``EngineHttpClient.refresh`` sends the refresh token in the
``Authorization`` header, but the engine's ``/api/v1/agent/auth/refresh``
expects a JSON body ``{"refreshToken": "..."}``. The refresh test below
exercises the engine contract directly via httpx rather than the broken
SDK helper so the contract regression check stays live. Fix the SDK in a
later phase that explicitly touches the agent HTTP layer.
"""

from __future__ import annotations

import httpx
import pytest

from myrmec.agent.http_client import EngineHttpClient

from .conftest import AgentFixture


@pytest.mark.asyncio
async def test_register_returns_tokens(agent_fixture: AgentFixture) -> None:
    """A freshly created agent can register and gets back JWT tokens."""
    client = EngineHttpClient(
        engine_url=agent_fixture.engine_url,
        registration_key=agent_fixture.registration_key,
    )
    try:
        response = await client.register(
            metadata={"test": "register-lifecycle"},
        )
    finally:
        await client.close()

    assert response.access_token, "access token must be present"
    assert response.refresh_token, "refresh token must be present"
    assert response.access_token != response.refresh_token
    assert response.instance_id is not None


@pytest.mark.asyncio
async def test_refresh_returns_new_tokens(agent_fixture: AgentFixture) -> None:
    """
    The engine's agent refresh endpoint mints a new access token from the
    refresh token. Bypasses the SDK helper (see KNOWN BUG in module
    docstring) and calls the endpoint directly so the engine-side contract
    stays under coverage.
    """
    client = EngineHttpClient(
        engine_url=agent_fixture.engine_url,
        registration_key=agent_fixture.registration_key,
    )
    try:
        registered = await client.register(
            metadata={"test": "refresh-lifecycle"},
        )
    finally:
        await client.close()

    async with httpx.AsyncClient(timeout=10.0) as http:
        resp = await http.post(
            f"{agent_fixture.engine_url}/api/v1/agent/auth/refresh",
            json={"refreshToken": registered.refresh_token},
        )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # The agent refresh contract returns only the new access token plus
    # its expiry; refresh tokens are not rotated. This is by design (the
    # ``RefreshResponse`` DTO doesn't carry ``refreshToken``) — if it ever
    # grows that field, broaden this assertion.
    assert body["accessToken"]
    assert "accessTokenExpiresAt" in body
    # NOTE: we don't assert that the access token differs from the original
    # one. JWTs include only second-level ``iat``/``exp`` resolution and
    # carry no nonce, so a refresh that happens in the same second as the
    # original ``register`` produces a byte-identical token. Verifying that
    # the endpoint returns 200 and a non-empty access token is the
    # contractually meaningful check.

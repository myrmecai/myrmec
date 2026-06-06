"""HTTP client for Myrmec Control Plane communication."""

import httpx
from pydantic import BaseModel
from typing import Any, Optional
import logging

logger = logging.getLogger(__name__)


class RegistrationResponse(BaseModel):
    agent_id: str
    access_token: str
    access_token_expires_at: str
    refresh_token: str
    refresh_token_expires_at: str


class RefreshResponse(BaseModel):
    access_token: str
    access_token_expires_at: str


class HeartbeatResponse(BaseModel):
    ack: bool
    server_time: str


class AgentInfoResponse(BaseModel):
    id: str
    name: str
    status: str
    registered_at: str
    last_heartbeat_at: Optional[str] = None
    metadata: Optional[dict[str, Any]] = None


class ControlPlaneClient:
    """HTTP client for Control Plane API."""

    def __init__(self, base_url: str, timeout: float = 10.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._access_token: Optional[str] = None
        self._refresh_token: Optional[str] = None
        self._client = httpx.Client(timeout=timeout)

    def set_tokens(self, access_token: str, refresh_token: str) -> None:
        """Set the JWT tokens for authenticated requests."""
        self._access_token = access_token
        self._refresh_token = refresh_token

    def _auth_headers(self) -> dict[str, str]:
        """Get authorization headers."""
        if self._access_token:
            return {"Authorization": f"Bearer {self._access_token}"}
        return {}

    def register(
        self,
        registration_key: str,
        name: str,
        metadata: Optional[dict[str, Any]] = None,
    ) -> RegistrationResponse:
        """Register agent with the control plane."""
        url = f"{self.base_url}/api/v1/agent/auth/register"
        payload = {
            "registrationKey": registration_key,
            "name": name,
            "metadata": metadata or {},
        }

        response = self._client.post(url, json=payload)
        response.raise_for_status()

        data = response.json()
        return RegistrationResponse(
            agent_id=data["agentId"],
            access_token=data["accessToken"],
            access_token_expires_at=data["accessTokenExpiresAt"],
            refresh_token=data["refreshToken"],
            refresh_token_expires_at=data["refreshTokenExpiresAt"],
        )

    def refresh_access_token(self) -> RefreshResponse:
        """Refresh the access token using the refresh token."""
        if not self._refresh_token:
            raise ValueError("No refresh token available")

        url = f"{self.base_url}/api/v1/agent/auth/refresh"
        payload = {"refreshToken": self._refresh_token}

        response = self._client.post(url, json=payload)
        response.raise_for_status()

        data = response.json()
        result = RefreshResponse(
            access_token=data["accessToken"],
            access_token_expires_at=data["accessTokenExpiresAt"],
        )
        # Update stored access token
        self._access_token = result.access_token
        return result

    def heartbeat(self, status: str = "ONLINE", current_task_id: Optional[str] = None) -> HeartbeatResponse:
        """Send heartbeat to control plane."""
        url = f"{self.base_url}/api/v1/agent/heartbeat"
        payload = {}
        if status:
            payload["status"] = status
        if current_task_id:
            payload["currentTaskId"] = current_task_id

        response = self._client.post(url, json=payload, headers=self._auth_headers())
        response.raise_for_status()

        data = response.json()
        return HeartbeatResponse(ack=data["ack"], server_time=data["serverTime"])

    def get_me(self) -> AgentInfoResponse:
        """Get current agent info."""
        url = f"{self.base_url}/api/v1/agent/me"

        response = self._client.get(url, headers=self._auth_headers())
        response.raise_for_status()

        data = response.json()
        return AgentInfoResponse(
            id=data["id"],
            name=data["name"],
            status=data["status"],
            registered_at=data["registeredAt"],
            last_heartbeat_at=data.get("lastHeartbeatAt"),
            metadata=data.get("metadata"),
        )

    def close(self) -> None:
        """Close the HTTP client."""
        self._client.close()

"""
HTTP client for agent registration and token refresh.
"""

import socket
from typing import Any
from uuid import UUID

import httpx
from pydantic import BaseModel


class RegisterRequest(BaseModel):
    """Registration request payload."""
    hostname: str
    ip_address: str | None = None
    sdk_version: str
    metadata: dict[str, Any] | None = None


class RegisterResponse(BaseModel):
    """Registration response from Engine."""
    access_token: str
    refresh_token: str
    instance_id: UUID


class RefreshResponse(BaseModel):
    """Token refresh response from Engine."""
    access_token: str
    refresh_token: str


class EngineHttpClient:
    """
    HTTP client for agent registration and token management.
    
    Handles:
    - Initial registration with registration key
    - Token refresh using refresh token
    """
    
    def __init__(
        self,
        engine_url: str,
        registration_key: str,
        sdk_version: str = "0.1.0",
        timeout: float = 30.0,
    ):
        """
        Initialize HTTP client.
        
        Args:
            engine_url: Base URL of the Engine (e.g., "http://localhost:8080").
            registration_key: Agent registration key (myr_agent_...).
            sdk_version: SDK version to report.
            timeout: HTTP request timeout in seconds.
        """
        self._engine_url = engine_url.rstrip("/")
        self._registration_key = registration_key
        self._sdk_version = sdk_version
        self._timeout = timeout
        
        self._client = httpx.AsyncClient(timeout=timeout)
    
    async def register(
        self,
        metadata: dict[str, Any] | None = None,
    ) -> RegisterResponse:
        """
        Register agent instance with the Engine.
        
        Args:
            metadata: Optional metadata about this instance.
        
        Returns:
            RegisterResponse with access/refresh tokens and instance ID.
        
        Raises:
            httpx.HTTPStatusError: If registration fails.
        """
        hostname = socket.gethostname()
        ip_address = self._get_ip_address()
        
        request = RegisterRequest(
            hostname=hostname,
            ip_address=ip_address,
            sdk_version=self._sdk_version,
            metadata=metadata,
        )
        
        response = await self._client.post(
            f"{self._engine_url}/api/v1/agent/auth/register",
            json=request.model_dump(by_alias=True, exclude_none=True),
            headers={"X-Registration-Key": self._registration_key},
        )
        response.raise_for_status()
        
        data = response.json()
        return RegisterResponse(
            access_token=data["accessToken"],
            refresh_token=data["refreshToken"],
            instance_id=UUID(data["instanceId"]),
        )
    
    async def refresh(self, refresh_token: str) -> RefreshResponse:
        """
        Refresh access token using refresh token.
        
        Args:
            refresh_token: Current refresh token.
        
        Returns:
            RefreshResponse with new access/refresh tokens.
        
        Raises:
            httpx.HTTPStatusError: If refresh fails.
        """
        response = await self._client.post(
            f"{self._engine_url}/api/v1/agent/auth/refresh",
            headers={"Authorization": f"Bearer {refresh_token}"},
        )
        response.raise_for_status()
        
        data = response.json()
        return RefreshResponse(
            access_token=data["accessToken"],
            refresh_token=data["refreshToken"],
        )
    
    async def close(self) -> None:
        """Close the HTTP client."""
        await self._client.aclose()
    
    def _get_ip_address(self) -> str | None:
        """Get local IP address."""
        try:
            # Connect to a public DNS to determine local IP
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return None
    
    @property
    def engine_url(self) -> str:
        """Engine base URL."""
        return self._engine_url

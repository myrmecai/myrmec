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


class RetrievalHit(BaseModel):
    """A single retrieval hit returned by ``POST /api/v1/agent/retrieve``."""

    passage: str
    chunk_id: UUID
    source_id: UUID
    source_name: str
    locator: str
    score: float

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "RetrievalHit":
        """Build a hit from the raw engine JSON (camelCase keys)."""
        return cls(
            passage=payload["passage"],
            chunk_id=UUID(payload["chunkId"]),
            source_id=UUID(payload["sourceId"]),
            source_name=payload["sourceName"],
            locator=payload["locator"],
            score=float(payload["score"]),
        )



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

    async def retrieve(
        self,
        access_token: str,
        knowledge_base_id: UUID | str,
        query: str,
        top_k: int = 5,
        filters: dict[str, str] | None = None,
    ) -> list[RetrievalHit]:
        """
        Run a retrieval query against a knowledge base.

        Backs the ``ctx.retrieve()`` ergonomic helper that ships with the
        chat runtime in a later release. For now agents that want RAG
        grounding can call this directly with their current access token.

        Args:
            access_token: Agent access token (from ``register``/``refresh``).
            knowledge_base_id: Target knowledge base UUID.
            query: Free-text retrieval query.
            top_k: Maximum number of hits to return.
            filters: Optional provider-specific filters.

        Returns:
            Ordered list of ``RetrievalHit`` (highest-score first). Empty
            list if the provider returns nothing or fails — the engine
            surfaces provider failures as an empty 200 so a misbehaving
            knowledge base never aborts an agent run.

        Raises:
            httpx.HTTPStatusError: On 4xx (bad request / auth) responses.
        """
        body: dict[str, Any] = {
            "knowledgeBaseId": str(knowledge_base_id),
            "query": query,
            "topK": top_k,
        }
        if filters:
            body["filters"] = filters

        response = await self._client.post(
            f"{self._engine_url}/api/v1/agent/retrieve",
            json=body,
            headers={"Authorization": f"Bearer {access_token}"},
        )
        response.raise_for_status()
        return [RetrievalHit.from_payload(item) for item in response.json()]

    
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

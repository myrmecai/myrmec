"""Unit tests for EngineHttpClient.retrieve and RetrievalHit.

The end-to-end integration test that exercises the real
``/api/v1/agent/retrieve`` endpoint lives in
``e2e/test_retrieve.py`` and requires a running engine plus a seeded
knowledge base; this module keeps the fast offline coverage.
"""

from __future__ import annotations

from uuid import uuid4

import httpx
import pytest

from myrmec.agent.http_client import EngineHttpClient, RetrievalHit


def test_retrieval_hit_from_payload_parses_camelcase_keys() -> None:
    chunk_id = uuid4()
    source_id = uuid4()
    hit = RetrievalHit.from_payload({
        "passage": "Some passage",
        "chunkId": str(chunk_id),
        "sourceId": str(source_id),
        "sourceName": "docs",
        "locator": "docs/readme.md#L10",
        "score": 0.42,
    })

    assert hit.passage == "Some passage"
    assert hit.chunk_id == chunk_id
    assert hit.source_id == source_id
    assert hit.source_name == "docs"
    assert hit.locator == "docs/readme.md#L10"
    assert hit.score == pytest.approx(0.42)


async def test_retrieve_posts_to_agent_endpoint_with_bearer_token() -> None:
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["auth"] = request.headers.get("Authorization")
        import json
        captured["body"] = json.loads(request.content.decode("utf-8"))
        return httpx.Response(
            200,
            json=[{
                "passage": "Hello",
                "chunkId": str(uuid4()),
                "sourceId": str(uuid4()),
                "sourceName": "docs",
                "locator": "docs/a.md",
                "score": 0.9,
            }],
        )

    transport = httpx.MockTransport(handler)
    client = EngineHttpClient(
        engine_url="http://engine.local",
        registration_key="ignored",
    )
    # Swap real client for one wired to MockTransport.
    await client._client.aclose()
    client._client = httpx.AsyncClient(transport=transport)
    try:
        kb_id = uuid4()
        hits = await client.retrieve(
            access_token="access-token-xyz",
            knowledge_base_id=kb_id,
            query="how to register",
            top_k=3,
            filters={"category": "auth"},
        )
    finally:
        await client.close()

    assert len(hits) == 1
    assert hits[0].passage == "Hello"
    assert captured["url"] == "http://engine.local/api/v1/agent/retrieve"
    assert captured["auth"] == "Bearer access-token-xyz"
    assert captured["body"] == {
        "knowledgeBaseId": str(kb_id),
        "query": "how to register",
        "topK": 3,
        "filters": {"category": "auth"},
    }


async def test_retrieve_omits_filters_when_none() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        import json
        body = json.loads(request.content.decode("utf-8"))
        assert "filters" not in body
        return httpx.Response(200, json=[])

    transport = httpx.MockTransport(handler)
    client = EngineHttpClient(
        engine_url="http://engine.local",
        registration_key="ignored",
    )
    await client._client.aclose()
    client._client = httpx.AsyncClient(transport=transport)
    try:
        hits = await client.retrieve(
            access_token="t",
            knowledge_base_id=uuid4(),
            query="q",
        )
    finally:
        await client.close()

    assert hits == []

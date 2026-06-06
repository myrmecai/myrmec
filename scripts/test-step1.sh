#!/bin/bash
# Integration test script for Step 1: Agent Communication

set -e

BASE_URL="http://localhost:9090"
API_URL="$BASE_URL/api/v1"

echo "=== Myrmec Step 1 Integration Test ==="
echo ""

# 1. Create registration key
echo "1. Creating registration key..."
KEY_RESPONSE=$(curl -s -X POST "$API_URL/admin/registration-keys?label=test-key")
REG_KEY=$(echo "$KEY_RESPONSE" | grep -o '"keyValue":"[^"]*"' | cut -d'"' -f4)
echo "   Registration key: $REG_KEY"
echo ""

# 2. Register agent
echo "2. Registering agent..."
REGISTER_RESPONSE=$(curl -s -X POST "$API_URL/agents/register" \
  -H "Content-Type: application/json" \
  -H "X-Registration-Key: $REG_KEY" \
  -d '{"name": "test-agent-1", "metadata": {"version": "0.1.0", "capabilities": ["test"]}}')

AGENT_ID=$(echo "$REGISTER_RESPONSE" | grep -o '"agentId":"[^"]*"' | cut -d'"' -f4)
TOKEN=$(echo "$REGISTER_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
echo "   Agent ID: $AGENT_ID"
echo "   Token: ${TOKEN:0:50}..."
echo ""

# 3. Get agent info
echo "3. Getting agent info..."
ME_RESPONSE=$(curl -s -X GET "$API_URL/agents/me" \
  -H "Authorization: Bearer $TOKEN")
echo "   Response: $ME_RESPONSE"
echo ""

# 4. Send heartbeat
echo "4. Sending heartbeat..."
HB_RESPONSE=$(curl -s -X POST "$API_URL/agents/heartbeat" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"status": "ONLINE"}')
echo "   Response: $HB_RESPONSE"
echo ""

echo "=== All tests passed! ==="

// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.agent;

/**
 * Result of agent-host creation, containing the {@link AgentHost} and its
 * plaintext registration key. The key is only available at creation time and
 * never stored/returned again.
 */
public record AgentHostCreationResult(AgentHost agent, String registrationKey) {
}
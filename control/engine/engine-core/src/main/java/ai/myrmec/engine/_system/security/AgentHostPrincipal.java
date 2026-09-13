// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._system.security;

import ai.myrmec.engine.agent.AgentHost;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.security.Principal;
import java.util.UUID;

/**
 * Security principal representing an authenticated durable Agent Host
 * (unified protocol §4.1). The subject of HOST_JWT is agent_hosts.id; this
 * principal carries the resolved entity so evaluators and the host-control
 * handshake read hostType/ownership from the durable record, never from
 * claims (protocol §15.4).
 */
@Getter
@RequiredArgsConstructor
public class AgentHostPrincipal implements Principal {

    private final AgentHost host;
    private final String hostName;

    @Override
    public String getName() {
        return hostName;
    }

    public UUID getHostId() {
        return host.getId();
    }
}
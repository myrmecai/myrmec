// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.agent;

import ai.myrmec.engine.agent.dto.AgentResponse;
import ai.myrmec.engine.conversation.ConversationEventService;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.node.NodeRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock private AgentHostRepository agentRepository;
    @Mock private AgentRepository agentInstanceRepository;
    @Mock private AgentProfileRepository agentProfileRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private ConversationEventService conversationEventService;
    @Mock private NodeRegistryService nodeRegistryService;

    @InjectMocks private AgentService service;

    @Test
    void recordHostAnnounceUpdatesControlNodeAndHostTelemetry() {
        UUID hostId = UUID.randomUUID();
        AgentHost host = new AgentHost();
        host.setId(hostId);
        host.setName("Worker Host");

        Map<String, Object> provisions = Map.of("tools", List.of("git"), "runtime", List.of("java21"));
        Map<String, Object> reportedCapacity = Map.of("cpu", 8, "memoryGb", 32);
        when(agentRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(agentRepository.save(any(AgentHost.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeRegistryService.getSelfNodeId()).thenReturn("node-self");

        service.recordHostAnnounce(hostId, provisions, reportedCapacity);

        ArgumentCaptor<AgentHost> saved = ArgumentCaptor.forClass(AgentHost.class);
        verify(agentRepository).save(saved.capture());
        AgentHost persisted = saved.getValue();
        assertThat(persisted.getProvisions()).isEqualTo(provisions);
        assertThat(persisted.getReportedCapacity()).isEqualTo(reportedCapacity);
        assertThat(persisted.getControlNodeId()).isEqualTo("node-self");
    }

    @Test
    void agentResponseIncludesControlNodeId() {
        AgentHost host = new AgentHost();
        host.setId(UUID.randomUUID());
        host.setName("Worker Host");
        host.setControlNodeId("node-self");
        host.setCreatedAt(Instant.parse("2026-06-22T18:00:00Z"));
        host.setUpdatedAt(Instant.parse("2026-06-22T18:30:00Z"));

        AgentResponse response = AgentResponse.from(host);

        assertThat(response.getControlNodeId()).isEqualTo("node-self");
        assertThat(response.getCreatedAt()).isEqualTo(host.getCreatedAt());
        assertThat(response.getUpdatedAt()).isEqualTo(host.getUpdatedAt());
    }
}

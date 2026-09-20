// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.agent;

import ai.myrmec.engine.conversation.ConversationEventService;
import ai.myrmec.engine.conversation.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentHostServiceTest {

    @Mock private AgentHostRepository agentHostRepository;
    @Mock private AgentHostInstanceRepository agentHostInstanceRepository;
    @Mock private AgentRepository agentInstanceRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private ConversationEventService conversationEventService;

    @InjectMocks private AgentHostService service;

    /**
     * {@code recordHostAnnounce} and the host {@code controlNodeId} surface
     * are gone (changelog 030): routing truth lives on
     * {@code agent_host_instances.control_node_id} and host announces have
     * no callers — the host-telemetry tests died with the method.
     */
    @Test
    void serviceConstructsWithoutRetiredCollaborators() {
        assertThat(service).isNotNull();
    }
}

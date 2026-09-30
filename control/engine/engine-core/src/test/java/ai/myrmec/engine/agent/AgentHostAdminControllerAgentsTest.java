// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.dto.AgentResponse;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controller integration test for the agent-host Agents endpoint
 * (GET /admin/agent-hosts/{id}/agents) added with the Agent Host model: a host's
 * ephemeral {@link Agent} replicas and their runtime FSM status are now
 * queryable for operations.
 *
 * <p>Not annotated {@code @Transactional} — the embedded HTTP server runs on
 * a separate thread, so the rows must be committed before the call.</p>
 */
class AgentHostAdminControllerAgentsTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private AgentRepository instanceRepository;

    @Test
    void listsTheAgentReplicasOfAHostWithTheirFsmStatus() {
        AgentHost host = host();
        Agent idle = saveAgent(host.getId(), Agent.Status.IDLE, null);
        UUID conversationId = UUID.randomUUID();
        Agent bound = saveAgent(host.getId(), Agent.Status.BOUND, conversationId);

        ResponseEntity<List<AgentResponse>> resp = restTemplate.exchange(
                "/api/v1/admin/agent-hosts/" + host.getId() + "/agents",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<AgentResponse>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(resp.getBody()).extracting(AgentResponse::id)
                .containsExactlyInAnyOrder(idle.getId(), bound.getId());
        assertThat(resp.getBody()).extracting(AgentResponse::status)
                .containsExactlyInAnyOrder("IDLE", "BOUND");
        assertThat(resp.getBody()).allSatisfy(a ->
                assertThat(a.agentHostId()).isEqualTo(host.getId()));
        AgentResponse boundDto = resp.getBody().stream()
                .filter(a -> "BOUND".equals(a.status())).findFirst().orElseThrow();
        assertThat(boundDto.conversationId()).isEqualTo(conversationId);
    }

    @Test
    void unknownHostReturns404() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/agent-hosts/" + UUID.randomUUID() + "/agents",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unauthenticatedCallIsRejected() {
        AgentHost host = host();
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/agent-hosts/" + host.getId() + "/agents",
                HttpMethod.GET,
                new HttpEntity<>((Object) null),
                String.class);

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    private AgentHost host() {
        Project project = data.project().named("agents-" + UUID.randomUUID()).create();
        return data.agent()
                .named("agents-host-" + UUID.randomUUID())
                .inProject(project)
                .create()
                .agent();
    }

    private Agent saveAgent(UUID hostId, Agent.Status status, UUID conversationId) {
        Agent agent = new Agent();
        agent.setAgentHostId(hostId);
        agent.setConversationId(conversationId);
        agent.setHostname("agent-host");
        agent.setRuntimeVersion("0.0.0");
        agent.setStatus(status);
        agent.setRegisteredAt(Instant.now().minus(1, ChronoUnit.HOURS));
        agent.setLastHeartbeatAt(Instant.now());
        return instanceRepository.save(agent);
    }
}

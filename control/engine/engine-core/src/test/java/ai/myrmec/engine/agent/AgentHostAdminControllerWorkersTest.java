package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.dto.AgentWorkerResponse;
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
 * Controller integration test for the agent-host worker-replicas endpoint
 * (GET /admin/agent-hosts/{id}/workers) added with the Agent Host model: a host's
 * ephemeral {@link Agent} replicas and their runtime FSM status are now
 * queryable for operations.
 *
 * <p>Not annotated {@code @Transactional} — the embedded HTTP server runs on
 * a separate thread, so the rows must be committed before the call.</p>
 */
class AgentHostAdminControllerWorkersTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private AgentRepository instanceRepository;

    @Test
    void listsTheWorkerReplicasOfAHostWithTheirFsmStatus() {
        AgentHost host = host();
        Agent idle = saveWorker(host.getId(), Agent.Status.IDLE, null);
        UUID conversationId = UUID.randomUUID();
        Agent bound = saveWorker(host.getId(), Agent.Status.BOUND, conversationId);

        ResponseEntity<List<AgentWorkerResponse>> resp = restTemplate.exchange(
                "/api/v1/admin/agent-hosts/" + host.getId() + "/workers",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<AgentWorkerResponse>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(resp.getBody()).extracting(AgentWorkerResponse::id)
                .containsExactlyInAnyOrder(idle.getId(), bound.getId());
        assertThat(resp.getBody()).extracting(AgentWorkerResponse::status)
                .containsExactlyInAnyOrder("IDLE", "BOUND");
        assertThat(resp.getBody()).allSatisfy(w ->
                assertThat(w.agentHostId()).isEqualTo(host.getId()));
        AgentWorkerResponse boundDto = resp.getBody().stream()
                .filter(w -> "BOUND".equals(w.status())).findFirst().orElseThrow();
        assertThat(boundDto.conversationId()).isEqualTo(conversationId);
    }

    @Test
    void unknownHostReturns404() {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/agent-hosts/" + UUID.randomUUID() + "/workers",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unauthenticatedCallIsRejected() {
        AgentHost host = host();
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/admin/agent-hosts/" + host.getId() + "/workers",
                HttpMethod.GET,
                new HttpEntity<>((Object) null),
                String.class);

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    private AgentHost host() {
        AgentProfile profile = data.agentProfile()
                .named("workers-profile-" + UUID.randomUUID())
                .withSystemPrompt("test")
                .create();
        Project project = data.project().named("workers-" + UUID.randomUUID()).create();
        return data.agent()
                .named("workers-host-" + UUID.randomUUID())
                .withProfile(profile)
                .inProject(project)
                .create()
                .agent();
    }

    private Agent saveWorker(UUID hostId, Agent.Status status, UUID conversationId) {
        Agent worker = new Agent();
        worker.setAgentHostId(hostId);
        worker.setConversationId(conversationId);
        worker.setHostname("worker-host");
        worker.setRuntimeVersion("0.0.0");
        worker.setStatus(status);
        worker.setRegisteredAt(Instant.now().minus(1, ChronoUnit.HOURS));
        worker.setLastHeartbeatAt(Instant.now());
        return instanceRepository.save(worker);
    }
}

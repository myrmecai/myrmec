package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentCreationResult;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.knowledge.rag.dto.RetrievalRequest;
import ai.myrmec.engine.knowledge.rag.dto.RetrievalResponse;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code POST /api/v1/agent/retrieve} endpoint
 * that backs {@code ctx.retrieve()} in the Python SDK.
 *
 * <p>Verifies the full network path: agent JWT → SecurityConfig role check →
 * controller → dispatcher → stub provider → JSON response.</p>
 *
 * <p>NOT annotated {@code @Transactional} because the embedded HTTP server
 * runs in another thread and would not see uncommitted data; rows leak
 * between tests in the same JVM, which is the accepted pattern in this
 * codebase (see {@code AddressBookScenarioIT}).</p>
 */
class AgentRetrievalControllerTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private ManualConnector manualConnector;

    @Autowired
    private ConnectorDispatcher connectorDispatcher;

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private AgentInstanceRepository agentInstanceRepository;

    /** Seed an AgentInstance so JwtAuthenticationFilter can authenticate the JWT. */
    private AgentInstance seedAgentInstance() {
        AgentProfile profile = data.agentProfile().named("retrieve-test").create();
        AgentCreationResult result = data.agent()
                .named("retrieve-test-agent")
                .withProfile(profile)
                .create();
        AgentInstance instance = new AgentInstance();
        instance.setAgentId(result.agent().getId());
        instance.setHostname("unit-test");
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(AgentInstance.Status.OFFLINE);
        instance.setRegisteredAt(Instant.now());
        return agentInstanceRepository.save(instance);
    }

    @Test
    void agentRetrieveReturnsHitsFromKnowledgeBase() throws Exception {
        AgentInstance agentInstance = seedAgentInstance();
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "agent-retrieve-it-" + UUID.randomUUID().toString().substring(0, 8),
                "Built by AgentRetrievalControllerTest",
                StubRetrievalProvider.PROVIDER_ID,
                null);
        KnowledgeSource source = knowledgeBaseService.addSource(
                kb.getId(),
                ManualConnector.CONNECTOR_TYPE,
                "docs",
                "memory://docs",
                null,
                null);
        manualConnector.stage(source.getId(),
                "docs/registration.md#L5",
                "Agents register via POST /api/v1/agent/auth/register using a registration key.",
                Map.of());
        connectorDispatcher.sync(source.getId());

        RetrievalRequest body = new RetrievalRequest(kb.getId(), "register agent", 5, Map.of());
        HttpEntity<RetrievalRequest> entity = new HttpEntity<>(body,
                agentHeaders(agentInstance.getId(), "test-agent"));

        ResponseEntity<List<RetrievalResponse>> response = restTemplate.exchange(
                "/api/v1/agent/retrieve",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<List<RetrievalResponse>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        RetrievalResponse hit = response.getBody().get(0);
        assertThat(hit.passage()).contains("register");
        assertThat(hit.locator()).isEqualTo("docs/registration.md#L5");
        assertThat(hit.sourceName()).isEqualTo("docs");
        assertThat(hit.score()).isGreaterThan(0.0);
    }

    @Test
    void agentRetrieveWithoutAuthIsRejected() {
        RetrievalRequest body = new RetrievalRequest(UUID.randomUUID(), "anything", 5, Map.of());
        HttpEntity<RetrievalRequest> entity = new HttpEntity<>(body);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/agent/retrieve",
                HttpMethod.POST,
                entity,
                String.class);

        // SecurityConfig requires AGENT role on /api/v1/agent/** — unauthenticated
        // requests are rejected with 401 (or 403 depending on filter order).
        assertThat(response.getStatusCode().value())
                .as("expected auth failure status code")
                .isIn(401, 403);
    }
}

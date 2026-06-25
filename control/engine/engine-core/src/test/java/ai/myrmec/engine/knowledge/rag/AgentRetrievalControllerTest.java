package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.TestDataFactory;
import ai.myrmec.engine.agent.AgentCreationResult;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.group.Group;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.knowledge.rag.dto.RetrievalRequest;
import ai.myrmec.engine.knowledge.rag.dto.RetrievalResponse;
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
    private AgentRepository agentInstanceRepository;

    @Autowired
    private GroupRepository groupRepository;

    /** Seed an AgentInstance so JwtAuthenticationFilter can authenticate the JWT. */
    private Agent seedAgentInstance() {
        return seedAgentInstanceInProject(null);
    }

    /** Seed an AgentInstance whose host belongs to {@code projectId} (nullable). */
    private Agent seedAgentInstanceInProject(UUID projectId) {
        AgentProfile profile = data.agentProfile().named("retrieve-test").create();
        var builder = data.agent()
                .named("retrieve-test-agent")
                .withProfile(profile);
        if (projectId != null) {
            builder = builder.inProject(projectId);
        }
        AgentCreationResult result = builder.create();
        Agent instance = new Agent();
        instance.setAgentHostId(result.agent().getId());
        instance.setHostname("unit-test");
        instance.setRuntimeVersion("0.0.0");
        instance.setStatus(Agent.Status.DEAD);
        instance.setRegisteredAt(Instant.now());
        return agentInstanceRepository.save(instance);
    }

    @Test
    void agentRetrieveReturnsHitsFromKnowledgeBase() throws Exception {
        Agent agentInstance = seedAgentInstance();
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

        RetrievalRequest body = new RetrievalRequest(kb.getId(), "register agent", 5, Map.of(), null, null);
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
        RetrievalRequest body = new RetrievalRequest(UUID.randomUUID(), "anything", 5, Map.of(), null, null);
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

    // --- ACL (#27) -----------------------------------------------------------

    @Test
    void agentInOwningProjectCanRetrieveProjectKb() throws Exception {
        Project project = newProject();
        Agent agent = seedAgentInstanceInProject(project.getId());
        KnowledgeBase kb = projectKbWithChunk(project.getId(),
                "Project-private runbook step.", "docs/runbook.md#L1");

        ResponseEntity<List<RetrievalResponse>> response = retrieve(kb.getId(), "runbook", agent);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).locator()).isEqualTo("docs/runbook.md#L1");
    }

    @Test
    void agentInAnotherProjectCannotRetrieveProjectKb() throws Exception {
        Project owner = newProject();
        Project other = newProject();
        Agent agent = seedAgentInstanceInProject(other.getId());
        KnowledgeBase kb = projectKbWithChunk(owner.getId(),
                "Confidential to owner project.", "docs/secret.md#L1");

        ResponseEntity<List<RetrievalResponse>> response = retrieve(kb.getId(), "confidential", agent);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void agentWithNoProjectCannotRetrieveProjectKb() throws Exception {
        Project owner = newProject();
        Agent agent = seedAgentInstance(); // host has no project
        KnowledgeBase kb = projectKbWithChunk(owner.getId(),
                "Project-only doc.", "docs/only.md#L1");

        ResponseEntity<List<RetrievalResponse>> response = retrieve(kb.getId(), "project", agent);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void agentCanRetrieveGroupKbInAncestorGroup() throws Exception {
        Group parent = newGroup("acl-parent", null);
        Group child = newGroup("acl-child", parent.getId());
        Project project = newProjectInGroup(child.getId());
        Agent agent = seedAgentInstanceInProject(project.getId());
        KnowledgeBase kb = groupKbWithChunk(parent.getId(),
                "Shared group playbook.", "docs/playbook.md#L1");

        ResponseEntity<List<RetrievalResponse>> response = retrieve(kb.getId(), "playbook", agent);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void retrieveFromUnknownKbReturnsEmpty() {
        Agent agent = seedAgentInstanceInProject(newProject().getId());

        ResponseEntity<List<RetrievalResponse>> response =
                retrieve(UUID.randomUUID(), "anything", agent);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    // --- helpers -------------------------------------------------------------

    private ResponseEntity<List<RetrievalResponse>> retrieve(UUID kbId, String query, Agent agent) {
        RetrievalRequest body = new RetrievalRequest(kbId, query, 5, Map.of(), null, null);
        HttpEntity<RetrievalRequest> entity = new HttpEntity<>(body,
                agentHeaders(agent.getId(), "test-agent"));
        return restTemplate.exchange(
                "/api/v1/agent/retrieve",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<List<RetrievalResponse>>() {});
    }

    private Project newProject() {
        return projectRepository.save(
                TestDataFactory.projectBuilder("acl-" + UUID.randomUUID().toString().substring(0, 8))
                        .build());
    }

    private Project newProjectInGroup(UUID groupId) {
        Project project = TestDataFactory.projectBuilder(
                "acl-grp-" + UUID.randomUUID().toString().substring(0, 8)).build();
        project.setGroupId(groupId);
        return projectRepository.save(project);
    }

    private Group newGroup(String namePrefix, UUID parentGroupId) {
        Group group = new Group();
        group.setName(namePrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        group.setParentGroupId(parentGroupId);
        return groupRepository.save(group);
    }

    private KnowledgeBase projectKbWithChunk(UUID projectId, String text, String locator) throws Exception {
        KnowledgeBase kb = knowledgeBaseService.createProjectBase(
                projectId,
                "acl-kb-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        stageAndSync(kb.getId(), text, locator);
        return kb;
    }

    private KnowledgeBase groupKbWithChunk(UUID groupId, String text, String locator) throws Exception {
        KnowledgeBase kb = knowledgeBaseService.createGroupBase(
                groupId,
                "acl-grp-kb-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        stageAndSync(kb.getId(), text, locator);
        return kb;
    }

    private void stageAndSync(UUID kbId, String text, String locator) throws Exception {
        KnowledgeSource source = knowledgeBaseService.addSource(
                kbId, ManualConnector.CONNECTOR_TYPE, "docs", "memory://docs", null, null);
        manualConnector.stage(source.getId(), locator, text, Map.of());
        connectorDispatcher.sync(source.getId());
    }
}

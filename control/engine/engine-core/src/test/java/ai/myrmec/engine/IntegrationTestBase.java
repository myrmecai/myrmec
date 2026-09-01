package ai.myrmec.engine;

import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.conversation.ConversationMessageRepository;
import ai.myrmec.engine.conversation.ConversationParticipantRepository;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.spi.crypto.EncryptionService;
import ai.myrmec.engine.spi.crypto.EncryptionService;
import ai.myrmec.engine.audit.AuditEventRepository;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelRepository;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Base class for integration tests.
 * Provides common utilities for test setup, authentication, and cleanup.
 * 
 * <p>Uses H2 in-memory database with PostgreSQL compatibility mode.
 * Each test class gets fresh database state via {@code @Transactional} or manual cleanup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
public abstract class IntegrationTestBase {

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JwtTokenProvider jwtTokenProvider;

    @Autowired
    protected ProjectRepository projectRepository;

    @Autowired
    protected AuditEventRepository auditEventRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected ModelRepository modelRepository;

    @Autowired
    protected EncryptionService encryptionService;

    @Autowired
    protected ConversationMessageRepository conversationMessageRepository;

    @Autowired
    protected ConversationParticipantRepository conversationParticipantRepository;

    @Autowired
    protected ConversationRepository conversationRepository;

    @Autowired
    protected ai.myrmec.engine.snapshot.ExecutionSnapshotRepository executionSnapshotRepository;

    @Autowired
    protected ai.myrmec.engine.context.ContextManifestRepository contextManifestRepository;

    @Autowired
    protected ai.myrmec.engine.inference.SessionRepository sessionRepository;

    @Autowired
    protected ai.myrmec.engine.quota.QuotaConsumptionRepository quotaConsumptionRepository;

    @Autowired
    protected ai.myrmec.engine.quota.QuotaRepository quotaRepository;

    @Autowired
    protected ai.myrmec.engine.serviceaccount.ServiceAccountRepository serviceAccountRepository;

    @Autowired
    protected ai.myrmec.engine.instruction.InstructionAssetRepository instructionAssetRepository;

    @Autowired
    protected ai.myrmec.engine.instruction.InstructionAssetVersionRepository instructionAssetVersionRepository;

    @Autowired
    protected ai.myrmec.engine.connection.ConnectionConfigRepository connectionConfigRepository;

    @Autowired
    protected ai.myrmec.engine.connection.ConnectionConfigVersionRepository connectionConfigVersionRepository;

    @Autowired
    protected ai.myrmec.engine.knowledge.KnowledgeProviderRepository knowledgeProviderRepository;

    @Autowired
    protected ai.myrmec.engine.knowledge.KnowledgeProviderVersionRepository knowledgeProviderVersionRepository;

    @Autowired
    protected ai.myrmec.engine.knowledge.KnowledgeSourceRepository knowledgeSourceRepository;

    @Autowired
    protected ai.myrmec.engine.knowledge.KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    protected ai.myrmec.engine.knowledge.DataFeedRepository dataFeedRepository;

    @Autowired
    protected ai.myrmec.engine.project.ProjectSettingRepository projectSettingRepository;

    @Autowired
    protected ai.myrmec.engine.project.ProjectInstructionBindingRepository projectInstructionBindingRepository;

    @Autowired
    protected ai.myrmec.engine.project.ProjectProviderBindingRepository projectProviderBindingRepository;

    @Autowired
    protected ai.myrmec.engine.assistant.AssistantContextBindingRepository assistantContextBindingRepository;

    @Autowired
    protected ai.myrmec.engine.assistant.AssistantVersionRepository assistantVersionRepository;

    @Autowired
    protected ai.myrmec.engine.assistant.AssistantGrantRepository assistantGrantRepository;

    @Autowired
    protected ai.myrmec.engine.assistant.AssistantRepository assistantRepository;

    @Autowired
    protected ai.myrmec.engine.agent.AgentProfileRepository agentProfileRepository;

    @Autowired
    protected ai.myrmec.engine.agent.AgentHostRepository agentHostRepository;

    @Autowired
    protected ai.myrmec.engine.agent.AgentRepository agentRepository;

    /**
     * Test admin - retrieved or created for E2E tests.
     */
    protected UUID TEST_ADMIN_ID;
    protected static final String TEST_ADMIN_NAME = "E2E Test Admin";
    protected static final String TEST_ADMIN_EMAIL = "admin@e2e-test.local";

    /**
     * Default test model code (GitHub Models GPT-4o).
     */
    protected static final String TEST_MODEL_CODE = "github-gpt-4o";

    /**
    /**
     * Clean up test data before each test, then create required fixtures.
     * Subclasses can override to add custom cleanup.
     */
    @BeforeEach
    void cleanupTestData() {
        // Delete in correct order to avoid FK violations.
        // Conversation tables reference projects, so they must go first.
        executionSnapshotRepository.deleteAllInBatch();
        // context_manifests references conversations(id); clear before conversations.
        contextManifestRepository.deleteAllInBatch();
        // sessions references projects(id); clear before projects.
        sessionRepository.deleteAllInBatch();
        quotaConsumptionRepository.deleteAllInBatch();
        quotaRepository.deleteAllInBatch();
        conversationMessageRepository.deleteAllInBatch();
        conversationParticipantRepository.deleteAllInBatch();
        conversationRepository.deleteAllInBatch();
        // service_accounts references projects(id); clear before projects.
        serviceAccountRepository.deleteAllInBatch();
        // New AI context tables — clear before projects (they have FKs to projects).
        dataFeedRepository.deleteAllInBatch();
        knowledgeChunkRepository.deleteAllInBatch();
        knowledgeSourceRepository.deleteAllInBatch();
        knowledgeProviderVersionRepository.deleteAllInBatch();
        knowledgeProviderRepository.deleteAllInBatch();
        connectionConfigVersionRepository.deleteAllInBatch();
        connectionConfigRepository.deleteAllInBatch();
        // project_instruction_bindings reference instruction_assets(id); clear before assets.
        projectInstructionBindingRepository.deleteAllInBatch();
        instructionAssetVersionRepository.deleteAllInBatch();
        instructionAssetRepository.deleteAllInBatch();
        // assistant_grants and assistant_versions reference assistants(id); clear before assistants.
        assistantGrantRepository.deleteAllInBatch();
        assistantVersionRepository.deleteAllInBatch();
        // assistants reference projects(id) and agent_profiles(id); clear before both.
        assistantRepository.deleteAllInBatch();
        // agent_hosts reference agent_profiles(id); clear before agent_profiles.
        agentHostRepository.deleteAllInBatch();
        // agents reference agent_hosts(id); clear before agent_hosts.
        agentRepository.deleteAllInBatch();
        // agent_profiles are referenced by assistants and agent_hosts; clear after both.
        agentProfileRepository.deleteAllInBatch();
        // project_settings, project_provider_bindings,
        // and assistant_context_bindings reference projects(id); clear before projects.
        projectSettingRepository.deleteAllInBatch();
        projectProviderBindingRepository.deleteAllInBatch();
        assistantContextBindingRepository.deleteAllInBatch();
        // audit_events references projects(id) and users(id); clear before projects.
        auditEventRepository.deleteAllInBatch();
        projectRepository.deleteAll();

        // Get or create the test admin user
        TEST_ADMIN_ID = userRepository.findByEmail(TEST_ADMIN_EMAIL)
                .map(User::getId)
                .orElseGet(this::createTestAdmin);
    }

    /**
     * Create the test admin user if it doesn't exist.
     * This handles cases where AdminUserBootstrap didn't run.
     */
    private UUID createTestAdmin() {
        User admin = new User();
        admin.setEmail(TEST_ADMIN_EMAIL);
        admin.setName(TEST_ADMIN_NAME);
        admin.setPasswordHash("$2a$10$dummy"); // Not used in tests
        admin.setProviderCode(ai.myrmec.engine.user.AuthenticationProvider.LOCAL_CODE);
        admin.setIsActive(true);
        admin.setIsSystem(true);
        admin.setCreatedAt(Instant.now());
        admin.setUpdatedAt(Instant.now());
        return userRepository.save(admin).getId();
    }

    // ==================== Authentication Helpers ====================

    /**
     * Get HTTP headers with admin JWT token.
     */
    protected HttpHeaders adminHeaders() {
        String token = jwtTokenProvider.generateUserAccessToken(
                TEST_ADMIN_ID,
                TEST_ADMIN_NAME,
                TEST_ADMIN_EMAIL,
                List.of("sys:PLATFORM_ADMIN", "sys:ORG_ADMIN"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    /**
     * Get HTTP headers with user JWT token having project-scoped EDITOR role.
     */
    protected HttpHeaders userHeaders(UUID userId, UUID projectId) {
        String token = jwtTokenProvider.generateUserAccessToken(
                userId,
                "Test User",
                "user@test.local",
                List.of("proj:" + projectId + ":EDITOR"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    /**
     * Get HTTP headers with agent JWT token.
     */
    protected HttpHeaders agentHeaders(UUID agentId, String agentName) {
        String token = jwtTokenProvider.generateAgentAccessToken(agentId, agentName);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    // ==================== Assertion Helpers ====================

    /**
     * Assert that a collection contains an item with the given name.
     */
    protected <T> void assertContainsName(Iterable<T> items, String name, java.util.function.Function<T, String> nameExtractor) {
        boolean found = false;
        for (T item : items) {
            if (name.equals(nameExtractor.apply(item))) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AssertionError("Expected to find item with name '" + name + "' but was not found");
        }
    }

    /**
     * Assert that a collection does NOT contain an item with the given name.
     */
    protected <T> void assertNotContainsName(Iterable<T> items, String name, java.util.function.Function<T, String> nameExtractor) {
        for (T item : items) {
            if (name.equals(nameExtractor.apply(item))) {
                throw new AssertionError("Expected NOT to find item with name '" + name + "' but it was present");
            }
        }
    }
}

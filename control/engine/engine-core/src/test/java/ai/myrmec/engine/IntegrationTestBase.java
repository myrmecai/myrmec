package ai.myrmec.engine;

import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine._system.crypto.EncryptionService;
import ai.myrmec.engine.knowledge.KnowledgeDocumentRepository;
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
    protected KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected ModelRepository modelRepository;

    @Autowired
    protected EncryptionService encryptionService;

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
     * GitHub Models API key for E2E tests.
     * Read from the {@code GITHUB_MODELS_API_KEY} environment variable so no secret
     * lives in version control. When unset the model is left with an empty API key,
     * and any test that genuinely needs to call GitHub Models will fail with a clear
     * authentication error rather than a misleading null.
     */
    private static final String TEST_GITHUB_MODELS_API_KEY =
            System.getenv().getOrDefault("GITHUB_MODELS_API_KEY", "");

    /**
     * Clean up test data before each test, then create required fixtures.
     * Subclasses can override to add custom cleanup.
     */
    @BeforeEach
    void cleanupTestData() {
        // Delete in correct order to avoid FK violations
        knowledgeDocumentRepository.deleteAll();
        projectRepository.deleteAll();

        // Get or create the test admin user
        TEST_ADMIN_ID = userRepository.findByEmail(TEST_ADMIN_EMAIL)
                .map(User::getId)
                .orElseGet(this::createTestAdmin);

        // Ensure test model has API key configured
        ensureTestModelApiKey();
    }

    /**
     * Ensure the test model (github-gpt-4o) has API key set.
     * The model is seeded by Liquibase, but API key is set here for tests.
     */
    private void ensureTestModelApiKey() {
        if (TEST_GITHUB_MODELS_API_KEY.isEmpty()) {
            return;
        }
        modelRepository.findById(TEST_MODEL_CODE).ifPresent(model -> {
            if (model.getApiKeyEncrypted() == null) {
                model.setApiKeyEncrypted(encryptionService.encrypt(TEST_GITHUB_MODELS_API_KEY));
                modelRepository.save(model);
            }
        });
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

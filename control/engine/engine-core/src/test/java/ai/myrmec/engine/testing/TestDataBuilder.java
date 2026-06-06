package ai.myrmec.engine.testing;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileService;
import ai.myrmec.engine.agent.AgentService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectService;
import ai.myrmec.engine.project.dto.CreateProjectRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fluent builder for test fixtures used by {@code @SpringBootTest}-style
 * integration tests. Replaces the long imperative
 * {@code scripts/setup-e2e-data.ps1} workflow for Java-side tests by giving
 * test authors a declarative API that delegates to the real production
 * services (so the fixtures exercise the exact same code paths the API uses).
 *
 * <p>Typical usage from a subclass of {@code IntegrationTestBase}:</p>
 * <pre>{@code
 *   @Autowired TestDataBuilder data;
 *
 *   Project project = data.project().named("Reservoir").create();
 *   AgentProfile profile = data.agentProfile().named("solver").create();
 *   AgentCreationResult agent = data.agent()
 *           .named("agent-1")
 *           .withProfile(profile)
 *           .inProject(project)
 *           .create();
 * }</pre>
 *
 * <p>Builders auto-suffix the name with a per-VM monotonic counter so two
 * tests in the same JVM session don't collide on the
 * {@code AgentService.existsByName} guard. Pass an explicit name suffix
 * via the {@code uniquelyNamed} variants only when the test needs to assert
 * a specific name shape.</p>
 *
 * <p>Defined inside the test source tree intentionally — this is a test
 * utility, not part of the production engine surface, and should not appear
 * on the engine-core or engine-spi classpath of consumers.</p>
 */
@Component
public class TestDataBuilder {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final ProjectService projectService;
    private final AgentProfileService agentProfileService;
    private final AgentService agentService;

    @Autowired
    public TestDataBuilder(
            ProjectService projectService,
            AgentProfileService agentProfileService,
            AgentService agentService) {
        this.projectService = projectService;
        this.agentProfileService = agentProfileService;
        this.agentService = agentService;
    }

    /** Start a new project builder. */
    public ProjectBuilder project() {
        return new ProjectBuilder();
    }

    /** Start a new agent-profile builder. */
    public AgentProfileBuilder agentProfile() {
        return new AgentProfileBuilder();
    }

    /** Start a new agent builder. */
    public AgentBuilder agent() {
        return new AgentBuilder();
    }

    private static String unique(String base) {
        return base + "-" + SEQUENCE.incrementAndGet();
    }

    // ====================================================================
    // Project builder
    // ====================================================================

    /**
     * Builder for {@link Project} fixtures. Delegates to
     * {@link ProjectService#create(CreateProjectRequest)} so the fixture
     * exercises the same validation + group-resolution code as the REST
     * endpoint.
     */
    public final class ProjectBuilder {
        private String name = "test-project";
        private String description = "Test project fixture";
        private boolean autoSuffix = true;
        private UUID groupId;
        private String workspaceRepoUrl;
        private String workspaceRepoBranch;

        /** Set the project name verbatim (no monotonic suffix). */
        public ProjectBuilder uniquelyNamed(String exact) {
            this.name = exact;
            this.autoSuffix = false;
            return this;
        }

        /** Set the project name; a monotonic suffix is appended. */
        public ProjectBuilder named(String base) {
            this.name = base;
            this.autoSuffix = true;
            return this;
        }

        public ProjectBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        public ProjectBuilder inGroup(UUID groupId) {
            this.groupId = groupId;
            return this;
        }

        public ProjectBuilder withRepo(String url, String branch) {
            this.workspaceRepoUrl = url;
            this.workspaceRepoBranch = branch;
            return this;
        }

        public Project create() {
            CreateProjectRequest request = new CreateProjectRequest();
            request.setName(autoSuffix ? unique(name) : name);
            request.setDescription(description);
            request.setGroupId(groupId);
            request.setWorkspaceRepoUrl(workspaceRepoUrl);
            request.setWorkspaceRepoBranch(workspaceRepoBranch);
            return projectService.create(request);
        }
    }

    // ====================================================================
    // Agent profile builder
    // ====================================================================

    /**
     * Builder for {@link AgentProfile} fixtures. Delegates to
     * {@link AgentProfileService#createProfile} which validates name
     * uniqueness and resolves tool codes through the real repository.
     */
    public final class AgentProfileBuilder {
        private String name = "test-profile";
        private boolean autoSuffix = true;
        private String description = "Test agent profile";
        private List<String> capabilities = List.of();
        private List<String> supportedTools = List.of();
        private Set<String> toolCodes = Set.of();
        private String systemPrompt = "You are a test agent.";
        // The Liquibase seed populates ``github-gpt-4o``; tests that need a
        // different model should override here explicitly.
        private String defaultModel = "github-gpt-4o";

        public AgentProfileBuilder named(String base) {
            this.name = base;
            this.autoSuffix = true;
            return this;
        }

        public AgentProfileBuilder uniquelyNamed(String exact) {
            this.name = exact;
            this.autoSuffix = false;
            return this;
        }

        public AgentProfileBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        public AgentProfileBuilder withCapabilities(List<String> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public AgentProfileBuilder withToolCodes(Set<String> toolCodes) {
            this.toolCodes = toolCodes;
            return this;
        }

        public AgentProfileBuilder withSystemPrompt(String prompt) {
            this.systemPrompt = prompt;
            return this;
        }

        public AgentProfileBuilder withDefaultModel(String modelCode) {
            this.defaultModel = modelCode;
            return this;
        }

        public AgentProfile create() {
            String effectiveName = autoSuffix ? unique(name) : name;
            return agentProfileService.createProfile(
                    effectiveName,
                    description,
                    capabilities,
                    supportedTools,
                    toolCodes,
                    systemPrompt,
                    defaultModel
            );
        }
    }

    // ====================================================================
    // Agent builder
    // ====================================================================

    /**
     * Builder for {@link Agent} fixtures. Yields the {@link
     * AgentCreationResult} (agent + plaintext registration key) so tests
     * that need to drive the agent SDK against the freshly-created agent
     * can read the key directly.
     */
    public final class AgentBuilder {
        private String name = "test-agent";
        private boolean autoSuffix = true;
        private String description = "Test agent";
        private AgentProfile profile;
        private UUID profileId;
        private UUID projectId;
        private String modelOverride;
        private Integer maxInstances = 1;

        public AgentBuilder named(String base) {
            this.name = base;
            this.autoSuffix = true;
            return this;
        }

        public AgentBuilder uniquelyNamed(String exact) {
            this.name = exact;
            this.autoSuffix = false;
            return this;
        }

        public AgentBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        public AgentBuilder withProfile(AgentProfile profile) {
            this.profile = profile;
            this.profileId = profile.getId();
            return this;
        }

        public AgentBuilder withProfileId(UUID profileId) {
            this.profileId = profileId;
            return this;
        }

        public AgentBuilder inProject(Project project) {
            this.projectId = project.getId();
            return this;
        }

        public AgentBuilder inProject(UUID projectId) {
            this.projectId = projectId;
            return this;
        }

        public AgentBuilder withModelOverride(String modelCode) {
            this.modelOverride = modelCode;
            return this;
        }

        public AgentBuilder withMaxInstances(int max) {
            this.maxInstances = max;
            return this;
        }

        public AgentCreationResult create() {
            if (profileId == null) {
                throw new IllegalStateException(
                        "AgentBuilder requires a profile — call withProfile() or "
                                + "withProfileId() before create()");
            }
            String effectiveName = autoSuffix ? unique(name) : name;
            return agentService.createAgent(
                    effectiveName,
                    description,
                    profileId,
                    projectId,
                    modelOverride,
                    null,
                    maxInstances
            );
        }
    }
}

package ai.myrmec.engine.testing;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileService;
import ai.myrmec.engine.agent.AgentHostService;
import ai.myrmec.engine.knowledge.KnowledgeChunk;
import ai.myrmec.engine.knowledge.KnowledgeChunkRepository;
import ai.myrmec.engine.knowledge.KnowledgeProvider;
import ai.myrmec.engine.knowledge.KnowledgeProviderService;
import ai.myrmec.engine.knowledge.KnowledgeProviderVersion;
import ai.myrmec.engine.knowledge.KnowledgeSource;
import ai.myrmec.engine.knowledge.KnowledgeSourceService;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.context.ContextSnapshot;
import ai.myrmec.engine.governance.GovernanceProfileService;
import ai.myrmec.engine.instruction.InstructionAsset;
import ai.myrmec.engine.instruction.InstructionAssetService;
import ai.myrmec.engine.instruction.InstructionAssetVersion;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectInstructionBinding;
import ai.myrmec.engine.project.ProjectInstructionBindingRepository;
import ai.myrmec.engine.project.ProjectService;
import ai.myrmec.engine.project.dto.CreateProjectRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 *   AgentHostCreationResult agent = data.agent()
 *           .named("agent-1")
 *           .withProfile(profile)
 *           .inProject(project)
 *           .create();
 * }</pre>
 *
 * <p>Builders auto-suffix the name with a per-VM monotonic counter so two
 * tests in the same JVM session don't collide on the
 * {@code AgentHostService.existsByName} guard. Pass an explicit name suffix
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
    private final AgentHostService agentService;
    private final KnowledgeProviderService knowledgeProviderService;
    private final KnowledgeSourceService knowledgeSourceService;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final InstructionAssetService instructionAssetService;
    private final ProjectInstructionBindingRepository projectInstructionBindingRepository;
    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final GovernanceProfileService governanceProfileService;

    @Autowired
    public TestDataBuilder(
            ProjectService projectService,
            AgentProfileService agentProfileService,
            AgentHostService agentService,
            KnowledgeProviderService knowledgeProviderService,
            KnowledgeSourceService knowledgeSourceService,
            KnowledgeChunkRepository knowledgeChunkRepository,
            InstructionAssetService instructionAssetService,
            ProjectInstructionBindingRepository projectInstructionBindingRepository,
            ConversationService conversationService,
            ConversationRepository conversationRepository,
            GovernanceProfileService governanceProfileService) {
        this.projectService = projectService;
        this.agentProfileService = agentProfileService;
        this.agentService = agentService;
        this.knowledgeProviderService = knowledgeProviderService;
        this.knowledgeSourceService = knowledgeSourceService;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.instructionAssetService = instructionAssetService;
        this.projectInstructionBindingRepository = projectInstructionBindingRepository;
        this.conversationService = conversationService;
        this.conversationRepository = conversationRepository;
        this.governanceProfileService = governanceProfileService;
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

    /** Start a new knowledge-provider builder. */
    public KnowledgeProviderBuilder knowledgeProvider() {
        return new KnowledgeProviderBuilder();
    }

    /** Start a new knowledge-source builder. */
    public KnowledgeSourceBuilder knowledgeSource() {
        return new KnowledgeSourceBuilder();
    }

    /** Start a new knowledge-chunk builder. */
    public KnowledgeChunkBuilder knowledgeChunk() {
        return new KnowledgeChunkBuilder();
    }

    /** Start a new instruction-asset builder. */
    public InstructionAssetBuilder instructionAsset() {
        return new InstructionAssetBuilder();
    }

    /** Start a new instruction-asset-version builder. */
    public InstructionAssetVersionBuilder instructionAssetVersion() {
        return new InstructionAssetVersionBuilder();
    }

    /** Start a new conversation builder. */
    public ConversationBuilder conversation() {
        return new ConversationBuilder();
    }

    /** Set the org-default governance profile. */
    public void setDefaultGovernanceProfile(String profileCode) {
        governanceProfileService.setDefaultProfile(profileCode, null);
    }

    /** Reload a conversation and return its context snapshot. */
    public ContextSnapshot getConversationSnapshot(UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .map(Conversation::getContextSnapshot)
                .orElse(null);
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
     * Builder for {@link AgentHost} fixtures. Yields the {@link
 *   AgentHostCreationResult} (agent + plaintext registration key) so tests
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
        private Integer maxAgents = 1;

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

        public AgentBuilder withMaxAgents(int max) {
            this.maxAgents = max;
            return this;
        }

        public AgentHostCreationResult create() {
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
                    maxAgents
            );
        }
    }

    // ====================================================================
    // Knowledge provider builder
    // ====================================================================

    /**
     * Builder for {@link KnowledgeProvider} fixtures. Delegates to
     * {@link KnowledgeProviderService#create} which auto-creates the first
     * draft version (Pattern 4 §Rule 4).
     *
     * <p>By default the provider is created with scope ORGANIZATION and
     * type MANAGED. The auto-created draft can be published by calling
     * {@link #publishDraft()} on the builder before {@link #create()} —
     * the builder will configure the required responseMapping and invoke
     * {@link KnowledgeProviderService#publishDraft}.</p>
     */
    public final class KnowledgeProviderBuilder {
        private String name = "test-kp";
        private boolean autoSuffix = true;
        private String description = "Test knowledge provider";
        private String type = "MANAGED";
        private String scope = "ORGANIZATION";
        private UUID projectId;
        private UUID actorId;
        private String actorName = "Test Admin";
        private boolean publish = false;
        private Map<String, Object> config;

        public KnowledgeProviderBuilder named(String base) {
            this.name = base;
            this.autoSuffix = true;
            return this;
        }

        public KnowledgeProviderBuilder uniquelyNamed(String exact) {
            this.name = exact;
            this.autoSuffix = false;
            return this;
        }

        public KnowledgeProviderBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        public KnowledgeProviderBuilder withType(String type) {
            this.type = type;
            return this;
        }

        public KnowledgeProviderBuilder inProject(UUID projectId) {
            this.projectId = projectId;
            this.scope = "PROJECT";
            return this;
        }

        public KnowledgeProviderBuilder inProject(Project project) {
            return inProject(project.getId());
        }

        public KnowledgeProviderBuilder orgScoped() {
            this.scope = "ORGANIZATION";
            this.projectId = null;
            return this;
        }

        public KnowledgeProviderBuilder withActor(UUID actorId, String actorName) {
            this.actorId = actorId;
            this.actorName = actorName;
            return this;
        }

        /** Publish the auto-created draft after creation. */
        public KnowledgeProviderBuilder publishDraft() {
            this.publish = true;
            return this;
        }

        /** Provide explicit config for the draft (otherwise a default responseMapping is used). */
        public KnowledgeProviderBuilder withConfig(Map<String, Object> config) {
            this.config = config;
            return this;
        }

        public KnowledgeProvider create() {
            UUID effectiveActor = actorId != null ? actorId : null;
            String effectiveName = autoSuffix ? unique(name) : name;
            KnowledgeProvider provider = knowledgeProviderService.create(
                    scope, projectId, effectiveName, description, type,
                    effectiveActor, actorName);

            if (publish) {
                Map<String, Object> effectiveConfig = config != null ? config : Map.of(
                        "responseMapping", Map.of(
                                "hitsPath", "$.results",
                                "passagePath", "text",
                                "sourceNamePath", "title",
                                "locatorPath", "url"));
                knowledgeProviderService.updateDraft(
                        provider.getId(), null, effectiveConfig,
                        effectiveActor, actorName);
                KnowledgeProviderVersion published = knowledgeProviderService.publishDraft(
                        provider.getId(), effectiveActor, actorName);
                provider.setCurrentVersionId(published.getId());
                provider.setStatus("ACTIVE");
            }
            return provider;
        }
    }

    // ====================================================================
    // Knowledge source builder
    // ====================================================================

    /**
     * Builder for {@link KnowledgeSource} fixtures. Delegates to
     * {@link KnowledgeSourceService#create}. Requires a provider version
     * id — pass the id from a published version or the draft id.
     */
    public final class KnowledgeSourceBuilder {
        private String name = "test-source";
        private boolean autoSuffix = true;
        private String description = "Test knowledge source";
        private UUID providerVersionId;
        private String scope = "ORGANIZATION";
        private UUID projectId;
        private String availability = "GLOBAL";
        private Integer priority = 0;
        private Map<String, Object> config;
        private UUID actorId;
        private String actorName = "Test Admin";

        public KnowledgeSourceBuilder named(String base) {
            this.name = base;
            this.autoSuffix = true;
            return this;
        }

        public KnowledgeSourceBuilder uniquelyNamed(String exact) {
            this.name = exact;
            this.autoSuffix = false;
            return this;
        }

        public KnowledgeSourceBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        public KnowledgeSourceBuilder forVersion(UUID providerVersionId) {
            this.providerVersionId = providerVersionId;
            return this;
        }

        public KnowledgeSourceBuilder forVersion(KnowledgeProviderVersion version) {
            return forVersion(version.getId());
        }

        public KnowledgeSourceBuilder inProject(UUID projectId) {
            this.projectId = projectId;
            this.scope = "PROJECT";
            return this;
        }

        public KnowledgeSourceBuilder inProject(Project project) {
            return inProject(project.getId());
        }

        public KnowledgeSourceBuilder orgScoped() {
            this.scope = "ORGANIZATION";
            this.projectId = null;
            return this;
        }

        public KnowledgeSourceBuilder withAvailability(String availability) {
            this.availability = availability;
            return this;
        }

        public KnowledgeSourceBuilder withPriority(int priority) {
            this.priority = priority;
            return this;
        }

        public KnowledgeSourceBuilder withConfig(Map<String, Object> config) {
            this.config = config;
            return this;
        }

        public KnowledgeSourceBuilder withActor(UUID actorId, String actorName) {
            this.actorId = actorId;
            this.actorName = actorName;
            return this;
        }

        public KnowledgeSource create() {
            if (providerVersionId == null) {
                throw new IllegalStateException(
                        "KnowledgeSourceBuilder requires a provider version — "
                                + "call forVersion() before create()");
            }
            String effectiveName = autoSuffix ? unique(name) : name;
            return knowledgeSourceService.create(
                    scope, projectId, effectiveName, description,
                    providerVersionId, config, availability, priority,
                    actorId, actorName);
        }
    }

    // ====================================================================
    // Knowledge chunk builder
    // ====================================================================

    /**
     * Builder for {@link KnowledgeChunk} fixtures. Persists directly via
     * {@link KnowledgeChunkRepository#save} — chunks are low-level rows
     * written by the retrieval sync pipeline, not via a service layer.
     */
    public final class KnowledgeChunkBuilder {
        private UUID knowledgeSourceId;
        private String locator;
        private String content = "Test chunk content";
        private String contentHash;
        private Long sequenceNo = 0L;
        private String metadataJson;

        public KnowledgeChunkBuilder forSource(UUID knowledgeSourceId) {
            this.knowledgeSourceId = knowledgeSourceId;
            return this;
        }

        public KnowledgeChunkBuilder forSource(KnowledgeSource source) {
            return forSource(source.getId());
        }

        public KnowledgeChunkBuilder withLocator(String locator) {
            this.locator = locator;
            return this;
        }

        public KnowledgeChunkBuilder withContent(String content) {
            this.content = content;
            return this;
        }

        public KnowledgeChunkBuilder withSequenceNo(long sequenceNo) {
            this.sequenceNo = sequenceNo;
            return this;
        }

        public KnowledgeChunkBuilder withMetadataJson(String metadataJson) {
            this.metadataJson = metadataJson;
            return this;
        }

        public KnowledgeChunk create() {
            if (knowledgeSourceId == null) {
                throw new IllegalStateException(
                        "KnowledgeChunkBuilder requires a source — "
                                + "call forSource() before create()");
            }
            // Auto-generate a unique locator if not explicitly set, to avoid
            // violating the (knowledge_source_id, locator) unique index.
            String effectiveLocator = locator != null ? locator
                    : "doc.md#chunk-" + SEQUENCE.incrementAndGet();
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setKnowledgeSourceId(knowledgeSourceId);
            chunk.setLocator(effectiveLocator);
            chunk.setContent(content);
            chunk.setContentHash(contentHash != null ? contentHash
                    : Integer.toHexString(content.hashCode()));
            chunk.setSequenceNo(sequenceNo);
            chunk.setMetadataJson(metadataJson);
            return knowledgeChunkRepository.save(chunk);
        }
    }

    // ====================================================================
    // Instruction asset builder
    // ====================================================================

    public final class InstructionAssetBuilder {
        private String name = "test-instruction";
        private boolean autoSuffix = true;
        private String scope = "ORGANIZATION";
        private UUID projectId;
        private String description = "Test instruction";
        private String category = "GENERAL";
        private String availability = "REQUIRED";
        private String content = "Test instruction content";

        public InstructionAssetBuilder named(String base) {
            this.name = base;
            this.autoSuffix = true;
            return this;
        }

        public InstructionAssetBuilder uniquelyNamed(String exact) {
            this.name = exact;
            this.autoSuffix = false;
            return this;
        }

        public InstructionAssetBuilder scope(String scope) {
            this.scope = scope;
            return this;
        }

        public InstructionAssetBuilder inProject(UUID projectId) {
            this.projectId = projectId;
            this.scope = "PROJECT";
            return this;
        }

        public InstructionAssetBuilder withDescription(String description) {
            this.description = description;
            return this;
        }

        public InstructionAssetBuilder withCategory(String category) {
            this.category = category;
            return this;
        }

        public InstructionAssetBuilder availability(String availability) {
            this.availability = availability;
            return this;
        }

        public InstructionAssetBuilder content(String content) {
            this.content = content;
            return this;
        }

        public InstructionAsset create() {
            String effectiveName = autoSuffix ? unique(name) : name;
            InstructionAsset asset = instructionAssetService.create(
                    scope, projectId, effectiveName, description, category, null, "Test Admin");

            // Create draft with INLINE content and set availability
            instructionAssetService.createDraft(
                    asset.getId(), "INLINE",
                    Map.of("content", content),
                    null, null, availability, 0, null, null, "Test Admin");

            // Publish
            instructionAssetService.publishDraft(asset.getId(), null, "Test Admin");
            return asset;
        }
    }

    // ====================================================================
    // Instruction asset version builder
    // ====================================================================

    public final class InstructionAssetVersionBuilder {
        private UUID assetId;
        private String content = "Updated instruction content";

        public InstructionAssetVersionBuilder forAsset(InstructionAsset asset) {
            this.assetId = asset.getId();
            return this;
        }

        public InstructionAssetVersionBuilder forAsset(UUID assetId) {
            this.assetId = assetId;
            return this;
        }

        public InstructionAssetVersionBuilder content(String content) {
            this.content = content;
            return this;
        }

        public InstructionAssetVersion publish() {
            if (assetId == null) {
                throw new IllegalStateException(
                        "InstructionAssetVersionBuilder requires an asset — call forAsset() before publish()");
            }
            instructionAssetService.createDraft(
                    assetId, "INLINE",
                    Map.of("content", content),
                    null, null, null, 0, null, null, "Test Admin");
            return instructionAssetService.publishDraft(assetId, null, "Test Admin");
        }
    }

    // ====================================================================
    // Conversation builder
    // ====================================================================

    public final class ConversationBuilder {
        private UUID projectId;
        private UUID createdBy;
        private String title = "Test conversation";

        public ConversationBuilder inProject(UUID projectId) {
            this.projectId = projectId;
            return this;
        }

        public ConversationBuilder inProject(Project project) {
            return inProject(project.getId());
        }

        public ConversationBuilder createdBy(UUID userId) {
            this.createdBy = userId;
            return this;
        }

        public ConversationBuilder titled(String title) {
            this.title = title;
            return this;
        }

        public Conversation create() {
            if (projectId == null) {
                throw new IllegalStateException(
                        "ConversationBuilder requires a project — call inProject() before create()");
            }
            return conversationService.createConversation(projectId, createdBy, title);
        }
    }
}

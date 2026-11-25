// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.seed;

import ai.myrmec.engine._system.common.DomainConstants.ConnectionType;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.agent.AgentHostType;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.agent.AgentProfileVersionService;
import ai.myrmec.engine.agent.ModelAccessMode;
import ai.myrmec.engine.assistant.Assistant;
import ai.myrmec.engine.assistant.AssistantRepository;
import ai.myrmec.engine.assistant.AssistantService;
import ai.myrmec.engine.assistant.AssistantVersion;
import ai.myrmec.engine.assistant.AssistantVersionService;
import ai.myrmec.engine.connection.ConnectionConfig;
import ai.myrmec.engine.connection.ConnectionConfigService;
import ai.myrmec.engine.harness.AgentRoleType;
import ai.myrmec.engine.harness.Harness;
import ai.myrmec.engine.harness.HarnessAgentBinding;
import ai.myrmec.engine.harness.HarnessAgentBindingRepository;
import ai.myrmec.engine.harness.HarnessRepository;
import ai.myrmec.engine.model.DeploymentType;
import ai.myrmec.engine.model.HealthStatus;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelCapabilityBinding;
import ai.myrmec.engine.model.ModelCapabilityBindingId;
import ai.myrmec.engine.model.ModelCapabilityBindingRepository;
import ai.myrmec.engine.model.ModelProviderConfig;
import ai.myrmec.engine.model.ModelProviderConfigRepository;
import ai.myrmec.engine.model.ModelRepository;
import ai.myrmec.engine.model.ModelStatus;
import ai.myrmec.engine.registration.RegistrationKey;
import ai.myrmec.engine.registration.RegistrationKeyRepository;
import ai.myrmec.engine.secret.CredentialType;
import ai.myrmec.engine.secret.SecretBackend;
import ai.myrmec.engine.secret.SecretPayload;
import ai.myrmec.engine.secret.SecretRepository;
import ai.myrmec.engine.secret.SecretService;
import ai.myrmec.engine.secret.dto.CreateSecretRequest;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamRepository;
import ai.myrmec.engine.team.TeamService;
import ai.myrmec.engine.team.dto.CreateTeamRequest;
import ai.myrmec.engine.tool.RiskClass;
import ai.myrmec.engine.tool.Tool;
import ai.myrmec.engine.tool.ToolRepository;
import ai.myrmec.engine.tool.ToolStatus;
import ai.myrmec.engine.tool.ToolType;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds the {@code optional/} test data, driven by environment variables and
 * the static JSON metadata. This is the model-provider stage: discover the
 * distinct provider codes referenced by the three test models and upsert a
 * {@link ModelProviderConfig} for each (static fields from JSON, base URL
 * ignored).
 *
 * <p>The three models are described by the env family:
 * <ul>
 *   <li>{@code MYRMEC_TEST_PRIMARY_MODEL_PROVIDER}</li>
 *   <li>{@code MYRMEC_TEST_SUBAGENT1_MODEL_PROVIDER}</li>
 *   <li>{@code MYRMEC_TEST_SUBAGENT2_MODEL_PROVIDER}</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OptionalDataSeedService {

    /** Env prefixes for the three test models (primary + two sub-agents). */
    public static final List<String> MODEL_ENV_PREFIXES =
            List.of("MYRMEC_TEST_PRIMARY", "MYRMEC_TEST_SUBAGENT1", "MYRMEC_TEST_SUBAGENT2");

    private static final String PROVIDERS_JSON = "seed-data/optional/model-providers.json";
    private static final String MODELS_JSON = "seed-data/optional/models.json";
    private static final String PROFILES_JSON = "seed-data/optional/agent-profiles.json";
    private static final String TEAMS_JSON = "seed-data/optional/teams.json";
    private static final String TOOLS_JSON = "seed-data/optional/tools.json";
    private static final String ASSISTANT_JSON = "seed-data/optional/assistant.json";

    private final ModelProviderConfigRepository providerRepository;
    private final ModelRepository modelRepository;
    private final ModelCapabilityBindingRepository modelCapabilityBindingRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final AgentProfileVersionService agentProfileVersionService;
    private final TeamService teamService;
    private final TeamRepository teamRepository;
    private final ToolRepository toolRepository;
    private final AgentHostRepository agentHostRepository;
    private final RegistrationKeyRepository registrationKeyRepository;
    private final AssistantService assistantService;
    private final AssistantRepository assistantRepository;
    private final AssistantVersionService assistantVersionService;
    private final HarnessRepository harnessRepository;
    private final HarnessAgentBindingRepository harnessAgentBindingRepository;
    private final SecretService secretService;
    private final SecretRepository secretRepository;
    private final ConnectionConfigService connectionConfigService;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    /**
     * Seed all optional test data in dependency order: providers first, then
     * secrets + connection configs, then models. Later stages (agent profiles,
     * assistant) plug in here.
     */
    @Transactional
    public void seedTestData() {
        List<String> providerCodes = seedModelProviders();
        seedSecretsAndConnectionConfigs(providerCodes);
        seedModels();
        seedTools();
        Map<String, UUID> profileIds = seedAgentProfiles();
        Map<String, UUID> teamIds = seedTeams();
        seedAgentHost();
        seedAssistant(profileIds, teamIds);
        log.info("Optional test data seeded ({} provider(s))", providerCodes.size());
    }

    /**
     * Seed the assistant from {@code assistant.json}: create the parent row
     * (idempotent by team+name) and its role bindings (idempotent by
     * assistant+alias). References profiles by alias and the team by name.
     */
    @Transactional
    public void seedAssistant(Map<String, UUID> profileIds, Map<String, UUID> teamIds) {
        AssistantSeed seed = loadAssistantSeed();

        UUID teamId = teamIds.get(seed.assistant().team());
        if (teamId == null) {
            throw new IllegalStateException(
                    "Assistant references unknown team '" + seed.assistant().team() + "'");
        }

        Assistant assistant = assistantRepository
                .findByTeamIdAndNameIgnoreCaseAndArchivedAtIsNull(teamId, seed.assistant().name())
                .orElse(null);
        if (assistant == null) {
            assistant = assistantService.createAssistant(
                    teamId,
                    seed.assistant().name(),
                    seed.assistant().description(),
                    null); // no creator for seeded assistant
            log.info("Seeded assistant: {} ({})", assistant.getName(), assistant.getId());
        }

        // Bindings live on the assistant VERSION via a 1:1 harness. Idempotency:
        // if the assistant already has a published version, its harness is frozen.
        if (assistant.getCurrentVersionId() != null) {
            log.info("Assistant {} already published; harness frozen at version {}",
                    assistant.getId(), assistant.getCurrentVersionId());
            return;
        }

        // Attach bindings to the open draft's harness, then publish.
        AssistantVersion draft = assistantVersionService.getOpenDraft(assistant.getId());
        Harness harness = harnessRepository.findById(draft.getId()).orElse(null);
        if (harness == null) {
            harness = new Harness();
            harness.setId(draft.getId());
            harness.setOwnerType("assistant");
            harnessRepository.save(harness);
            log.info("Seeded harness for assistant version {}", draft.getId());
        }

        for (AssistantSeed.AgentRole role : seed.agentRoles()) {
            if (harnessAgentBindingRepository
                    .existsByHarnessIdAndAgentAlias(harness.getId(), role.agentAlias())) {
                continue;
            }
            UUID profileId = profileIds.get(role.profileAlias());
            if (profileId == null) {
                throw new IllegalStateException(
                        "Agent role '" + role.agentAlias() + "' references unknown profile alias '"
                                + role.profileAlias() + "'");
            }
            HarnessAgentBinding binding = new HarnessAgentBinding();
            binding.setHarnessId(harness.getId());
            binding.setAgentProfileId(profileId);
            binding.setRoleType(AgentRoleType.valueOf(role.roleType()));
            binding.setAgentAlias(role.agentAlias());
            binding.setPromptAddendum(role.promptAddendum());
            harnessAgentBindingRepository.save(binding);
            log.info("Bound role {} (alias {}) to harness {}", role.roleType(),
                    role.agentAlias(), harness.getId());
        }

        assistantVersionService.publish(assistant.getId(), null);
        log.info("Published seeded assistant {} with frozen harness bindings", assistant.getId());
    }

    /** Load the assistant seed. */
    private AssistantSeed loadAssistantSeed() {
        ClassPathResource resource = new ClassPathResource(ASSISTANT_JSON);
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, AssistantSeed.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read assistant seed: " + ASSISTANT_JSON, e);
        }
    }

    /**
     * Seed a MANAGED agent host using a fixed registration key (from
     * {@code MYRMEC_TEST_REGISTRATION_KEY}, default {@code myr_agent_test}) so
     * the mock agent can register against the real engine.
     *
     * <p>Also inserts the hashed {@code registration_keys} row so the key is
     * revocable/expirable like an admin-issued key. Idempotent by key.</p>
     */
    @Transactional
    public void seedAgentHost() {
        String key = env("MYRMEC_TEST_REGISTRATION_KEY");
        if (key == null || key.isBlank()) {
            key = "myr_agent_test";
        }
        key = key.trim();

        if (agentHostRepository.findByRegistrationKey(key).isEmpty()) {
            AgentHost host = new AgentHost();
            host.setName("Mock Agent Host");
            host.setDescription("Test MANAGED host matching the mock agent's registration key.");
            host.setRegistrationKey(key);
            host.setHostType(AgentHostType.MANAGED);
            host.setStatus(AgentHost.Status.ACTIVE);
            host.setMaxAgents(1);
            host.setModelAccessMode(ModelAccessMode.DIRECT);
            agentHostRepository.save(host);
            log.info("Seeded agent host with registration key: {}***", key.substring(0, Math.min(6, key.length())));
        }

        String keyHash = hashRegistrationKey(key);
        if (registrationKeyRepository.findByKeyHash(keyHash).isEmpty()) {
            RegistrationKey reg = new RegistrationKey();
            reg.setKeyHash(keyHash);
            reg.setLabel("Mock Agent");
            reg.setIsRevoked(false);
            registrationKeyRepository.save(reg);
            log.info("Seeded registration key hash for mock agent");
        }
    }

    /** SHA-256 (Base64) of a registration key, matching RegistrationKeyService.hash. */
    private String hashRegistrationKey(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Seed the tool catalog from {@code tools.json} (SDK tool names). Idempotent
     * by code; tools are inserted as system tools so they cannot be deleted.
     */
    @Transactional
    public void seedTools() {
        List<ToolSeed> tools = loadToolCatalog();
        for (ToolSeed seed : tools) {
            if (toolRepository.existsByCode(seed.code())) {
                continue;
            }
            Tool tool = new Tool();
            tool.setCode(seed.code());
            tool.setName(seed.name());
            tool.setDescription(seed.description());
            tool.setToolType(ToolType.valueOf(seed.toolType()));
            tool.setConfigSchema(seed.configSchema());
            tool.setDocsUrl(seed.docsUrl());
            tool.setSystem(true);
            tool.setStatus(ToolStatus.ACTIVE);
            tool.setRiskClass(seed.riskClass() == null ? RiskClass.SAFE : RiskClass.valueOf(seed.riskClass()));
            toolRepository.save(tool);
            log.info("Seeded tool: {}", seed.code());
        }
        log.info("Seeded {} tool(s)", tools.size());
    }

    /** Load the tool catalog (list). */
    private List<ToolSeed> loadToolCatalog() {
        ClassPathResource resource = new ClassPathResource(TOOLS_JSON);
        try (InputStream in = resource.getInputStream()) {
            JavaType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ToolSeed.class);
            return objectMapper.readValue(in, listType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read tools seed: " + TOOLS_JSON, e);
        }
    }

    /**
     * Seed the test teams from {@code teams.json}. Idempotent by name.
     *
     * @return map of team name → team id
     */
    @Transactional
    public Map<String, UUID> seedTeams() {
        List<TeamSeed> teams = loadTeamCatalog();
        Map<String, UUID> idsByName = new LinkedHashMap<>();

        for (TeamSeed seed : teams) {
            Team team = teamRepository.findByName(seed.name()).orElse(null);
            if (team == null) {
                CreateTeamRequest request = new CreateTeamRequest();
                request.setName(seed.name());
                request.setDescription(seed.description());
                request.setAllowedServiceTypes(seed.allowedServiceTypes());
                request.setGroupId(seed.groupId());
                request.setWorkspaceRepoUrl(seed.workspaceRepoUrl());
                request.setWorkspaceRepoBranch(seed.workspaceRepoBranch());
                request.setAutoHitlOnDestructive(seed.autoHitlOnDestructive());
                request.setAttachmentsEnabled(seed.attachmentsEnabled());
                request.setAttachmentRetentionTtlDays(seed.attachmentRetentionTtlDays());
                request.setAttachmentMaxFileSizeBytes(seed.attachmentMaxFileSizeBytes());
                request.setAttachmentTypeAllowlist(seed.attachmentTypeAllowlist());
                team = teamService.create(request);
                log.info("Seeded team: {} ({})", team.getName(), team.getId());
            }
            idsByName.put(team.getName(), team.getId());
        }

        return idsByName;
    }

    /** Load the team catalog (list). */
    private List<TeamSeed> loadTeamCatalog() {
        ClassPathResource resource = new ClassPathResource(TEAMS_JSON);
        try (InputStream in = resource.getInputStream()) {
            JavaType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, TeamSeed.class);
            return objectMapper.readValue(in, listType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read teams seed: " + TEAMS_JSON, e);
        }
    }

    /**
     * Seed the models referenced by the test-model env vars. Correlates each
     * env prefix to a {@code models.json} entry by {@code modelId}, then upserts
     * the {@link Model} and its {@code model_capability_bindings}.
     *
     * @return map of env prefix → model code (for the later agent-profile stage)
     */
    @Transactional
    public Map<String, String> seedModels() {
        Map<String, ModelSeed> catalog = loadModelCatalog();
        Map<String, String> seededByPrefix = new LinkedHashMap<>();

        for (String prefix : MODEL_ENV_PREFIXES) {
            String provider = env(prefix + "_MODEL_PROVIDER");
            String modelId = env(prefix + "_MODEL_ID");
            if (provider == null || provider.isBlank() || modelId == null || modelId.isBlank()) {
                continue;
            }
            modelId = modelId.trim();

            ModelSeed seed = catalog.get(modelId);
            if (seed == null) {
                log.warn("No models.json entry for env modelId '{}' (prefix {}); skipping",
                        modelId, prefix);
                continue;
            }

            String code = seed.code();
            if (modelRepository.existsByCode(code)) {
                seededByPrefix.put(prefix, code);
                continue;
            }

            Model model = new Model();
            model.setCode(code);
            model.setName(seed.name());
            model.setProvider(seed.provider());
            model.setModelId(seed.modelId());
            model.setApiEndpoint(resolveEndpoint(seed, prefix));
            model.setInfraConfig(seed.infraConfig());
            model.setDefaultParams(seed.defaultParams());
            model.setInputPrice(seed.inputPrice());
            model.setOutputPrice(seed.outputPrice());
            model.setCurrency(seed.currency() != null ? seed.currency() : "USD");
            model.setContextWindowTokens(seed.contextWindowTokens());
            model.setMaxOutputTokens(seed.maxOutputTokens());
            model.setTrainingCutoffDate(seed.trainingCutoffDate());
            model.setReleaseDate(seed.releaseDate());
            model.setStatus(ModelStatus.ACTIVE);
            model.setHealthStatus(HealthStatus.UNKNOWN);
            modelRepository.save(model);

            if (seed.capabilities() != null) {
                for (ModelSeed.ModelCapabilitySeed cap : seed.capabilities()) {
                    ModelCapabilityBindingId id = new ModelCapabilityBindingId(code, cap.code());
                    if (!modelCapabilityBindingRepository.existsById(id)) {
                        ModelCapabilityBinding binding = new ModelCapabilityBinding();
                        binding.setId(id);
                        binding.setParameters(cap.parameters());
                        modelCapabilityBindingRepository.save(binding);
                    }
                }
            }

            seededByPrefix.put(prefix, code);
            log.info("Seeded model: {} ({})", code, seed.modelId());
        }

        return seededByPrefix;
    }

    /** Per-model endpoint: env override wins, else the JSON {@code apiEndpoint}. */
    private String resolveEndpoint(ModelSeed seed, String prefix) {
        String endpoint = env(prefix + "_MODEL_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint.trim();
        }
        return seed.apiEndpoint();
    }

    /**
     * Seed the agent profiles from {@code agent-profiles.json}, publishing
     * version 1 for each. Idempotent by profile name.
     *
     * @return map of profile alias → profile id (for the assistant-binding stage,
     *         which references {@code agent_profiles.id})
     */
    @Transactional
    public Map<String, UUID> seedAgentProfiles() {
        List<AgentProfileSeed> profiles = loadProfileCatalog();
        Map<String, UUID> profileIdsByAlias = new LinkedHashMap<>();

        for (AgentProfileSeed seed : profiles) {
            AgentProfile profile = agentProfileRepository.findByName(seed.name()).orElse(null);
            if (profile == null) {
                profile = new AgentProfile();
                profile.setName(seed.name());
                profile.setDescription(seed.description());
                profile.setAddendumAllowed(seed.addendumAllowed());
                profile.setStatus(AgentProfile.Status.ACTIVE);
                profile = agentProfileRepository.save(profile);

                agentProfileVersionService.publishInitial(
                        profile.getId(),
                        seed.capabilities(),
                        new LinkedHashSet<>(seed.toolCodes() == null ? List.of() : seed.toolCodes()),
                        seed.systemPrompt(),
                        seed.defaultModel(),
                        interactionMode(seed.interactionMode()),
                        seed.commandTemplates(),
                        seed.approvalPolicy(),
                        seed.approvalRequestTtlSeconds());
                log.info("Seeded agent profile: {} ({})", seed.name(), profile.getId());
            }

            profileIdsByAlias.put(seed.alias(), profile.getId());
        }

        return profileIdsByAlias;
    }

    private AgentProfileVersion.InteractionMode interactionMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return AgentProfileVersion.InteractionMode.ONE_SHOT;
        }
        return AgentProfileVersion.InteractionMode.valueOf(mode.trim().toUpperCase());
    }

    /** Load the agent-profile catalog (list). */
    private List<AgentProfileSeed> loadProfileCatalog() {
        ClassPathResource resource = new ClassPathResource(PROFILES_JSON);
        try (InputStream in = resource.getInputStream()) {
            JavaType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, AgentProfileSeed.class);
            return objectMapper.readValue(in, listType);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read agent profiles seed: " + PROFILES_JSON, e);
        }
    }

    /** Load the model catalog, keyed by {@code modelId}. */
    private Map<String, ModelSeed> loadModelCatalog() {
        ClassPathResource resource = new ClassPathResource(MODELS_JSON);
        try (InputStream in = resource.getInputStream()) {
            JavaType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ModelSeed.class);
            List<ModelSeed> list = objectMapper.readValue(in, listType);
            Map<String, ModelSeed> map = new LinkedHashMap<>();
            for (ModelSeed seed : list) {
                map.put(seed.modelId(), seed);
            }
            return map;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read models seed: " + MODELS_JSON, e);
        }
    }

    /**
     * Seed the model providers referenced by the test-model env vars. Idempotent:
     * existing providers are left as-is; missing ones are inserted from JSON.
     *
     * @return the distinct provider codes that were seeded/referenced
     */
    @Transactional
    public List<String> seedModelProviders() {
        Map<String, ModelProviderSeed> catalog = loadProviderCatalog();
        Map<String, ModelProviderConfig> created = new LinkedHashMap<>();

        for (String prefix : MODEL_ENV_PREFIXES) {
            String code = env(prefix + "_MODEL_PROVIDER");
            if (code == null || code.isBlank()) {
                continue;
            }
            code = code.trim();
            if (providerRepository.existsByCode(code)) {
                continue;
            }
            ModelProviderConfig config = buildProvider(code, catalog.get(code));
            providerRepository.save(config);
            created.put(code, config);
            log.info("Seeded model provider: {} ({})", config.getName(), code);
        }

        log.info("Seeded {} model provider(s)", created.size());
        return created.keySet().stream().toList();
    }

    /**
     * Seed the global API-key secret and org-scoped connection config for each
     * provider, deduped by provider code. The connection config's {@code url}
     * is the provider base URL; the secret holds the API key from env.
     *
     * @param providerCodes distinct provider codes (from {@link #seedModelProviders()})
     * @return map of provider code → connection config id
     */
    @Transactional
    public Map<String, UUID> seedSecretsAndConnectionConfigs(List<String> providerCodes) {
        Map<String, ModelProviderSeed> catalog = loadProviderCatalog();
        Map<String, UUID> result = new LinkedHashMap<>();

        for (String code : providerCodes) {
            ModelProviderConfig provider = providerRepository.findById(code).orElse(null);
            if (provider == null) {
                continue;
            }
            // Already wired: skip.
            if (provider.getConnectionConfigId() != null) {
                result.put(code, provider.getConnectionConfigId());
                continue;
            }

            String secretName = code + "-api-key";
            UUID secretId;
            if (secretRepository.existsGlobalByName(secretName)) {
                secretId = secretRepository.findGlobalByName(secretName)
                        .map(s -> s.getId())
                        .orElse(null);
            } else {
                String apiKey = firstApiKeyForProvider(code);
                if (apiKey == null) {
                    // Keyless provider (e.g. local) — no secret needed.
                    secretId = null;
                } else {
                    CreateSecretRequest request = new CreateSecretRequest(
                            secretName,
                            CredentialType.API_KEY,
                            SecretBackend.LOCAL,
                            new SecretPayload.ApiKey(apiKey, null));
                    secretId = secretService.createGlobal(request, null).id();
                }
            }

            ModelProviderSeed seed = catalog.get(code);
            String baseUrl = seed != null ? seed.baseUrl() : null;
            String configName = code + "-connection";

            ConnectionConfig config = connectionConfigService.create(
                    Scope.ORGANIZATION, null, configName,
                    "Connection config for provider " + code,
                    ConnectionType.HTTP, secretId, null, "SYSTEM");

            connectionConfigService.publishSeedVersion(
                    config.getId(), baseUrl, Map.of(), null, "SYSTEM");

            provider.setConnectionConfigId(config.getId());
            providerRepository.save(provider);
            result.put(code, config.getId());

            log.info("Seeded secret + connection config for provider: {} (config: {})",
                    code, config.getId());
        }

        return result;
    }

    /** First non-blank API key across the three env prefixes for the given provider. */
    private String firstApiKeyForProvider(String providerCode) {
        for (String prefix : MODEL_ENV_PREFIXES) {
            String p = env(prefix + "_MODEL_PROVIDER");
            if (providerCode.equals(p == null ? null : p.trim())) {
                String key = env(prefix + "_MODEL_API_KEY");
                if (key != null && !key.isBlank()) {
                    return key;
                }
            }
        }
        return null;
    }

    private ModelProviderConfig buildProvider(String code, ModelProviderSeed seed) {
        ModelProviderConfig config = new ModelProviderConfig();
        config.setCode(code);
        // Static metadata from JSON when available; otherwise fall back to the code.
        config.setName(seed != null ? seed.name() : code);
        config.setBaseUrl(seed != null ? seed.baseUrl() : null);
        config.setDeploymentType(seed != null ? seed.deploymentType() : DeploymentType.CLOUD);
        config.setRequiresAuth(seed != null && seed.requiresAuth());
        config.setDocsUrl(seed != null ? seed.docsUrl() : null);
        config.setDescription(seed != null ? seed.description() : null);
        config.setSystem(false);
        config.setStatus(ModelStatus.ACTIVE);
        return config;
    }

    /** Load the static provider catalog, keyed by code. */
    private Map<String, ModelProviderSeed> loadProviderCatalog() {
        ClassPathResource resource = new ClassPathResource(PROVIDERS_JSON);
        try (InputStream in = resource.getInputStream()) {
            JavaType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ModelProviderSeed.class);
            List<ModelProviderSeed> list = objectMapper.readValue(in, listType);
            Map<String, ModelProviderSeed> map = new LinkedHashMap<>();
            for (ModelProviderSeed seed : list) {
                map.put(seed.code(), seed);
            }
            return map;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read model providers seed: " + PROVIDERS_JSON, e);
        }
    }

    private String env(String name) {
        return environment.getProperty(name);
    }
}
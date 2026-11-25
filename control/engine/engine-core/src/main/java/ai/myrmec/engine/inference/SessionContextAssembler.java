// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine._protocol.SessionCredential;
import ai.myrmec.engine._protocol.payload.SessionOpenPayload;
import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.agent.AgentProfileVersionService;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentProfileVersionRepository;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.assistant.AssistantAgentBinding;
import ai.myrmec.engine.assistant.AssistantAgentBindingRepository;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.context.InstructionAssetVersionResolver;
import ai.myrmec.engine.inference.security.CredentialEnvelopeService;
import ai.myrmec.engine.knowledge.KnowledgeSource;
import ai.myrmec.engine.knowledge.KnowledgeSourceRepository;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamRepository;
import ai.myrmec.engine.secret.SecretPayload;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.tool.Tool;
import ai.myrmec.engine.websocket.ChannelTokenService;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.UUID;

/**
 * Session context assembler (§6.1).
 *
 * <p>Builds a {@link SessionOpenPayload} for a conversation bind or workflow
 * execution assignment. Resolves model, workspace, tool catalog, and
 * knowledge-source handles. Creates a {@link Session} row with pinned
 * context.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionContextAssembler {

    private final ModelService modelService;
    private final TeamRepository teamRepository;
    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final AgentProfileVersionService agentProfileVersionService;
    private final SecretResolverService secretResolverService;
    private final InstructionAssetVersionResolver instructionAssetVersionResolver;
    private final SessionRepository sessionRepository;
    private final AgentHostInstanceRepository agentHostInstanceRepository;
    private final ai.myrmec.engine.agent.AgentHostRepository agentHostRepository;
    private final CredentialEnvelopeService credentialEnvelopeService;
    private final ChannelTokenService channelTokenService;
    private final AgentProfileVersionRepository agentProfileVersionRepository;
    private final AssistantAgentBindingRepository assistantAgentBindingRepository;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;
    private final ai.myrmec.engine.node.NodeRegistryService nodeRegistryService;

    /** credentialRef constants (design §9) — stable across both config blocks. */
    private static final String MODEL_CREDENTIAL_REF = "model-provider-token";
    private static final String WORKSPACE_CREDENTIAL_REF = "repo-token";
    private static final String PURPOSE_MODEL = "MODEL_PROVIDER";
    private static final String PURPOSE_WORKSPACE = "WORKSPACE_TOKEN";

    @Value("${myrmec.channel.endpoint:}")
    private String channelEndpoint;
    /**
     * Task 11 (§22.3/D7): the channel offer's URL scheme. The production
     * default is {@code ws} (plain WebSocket over the engine's HTTP port;
     * the Spring WebSocket endpoint at {@code /api/v1/agent/ws}
     * is not SSL-terminated). {@code wss} is the explicit opt-in for
     * deployments that terminate TLS in front of the engine.
     */
    @Value("${myrmec.channel.scheme:ws}")
    private String channelScheme;

    /**
     * §7.3 (Wave 6, A4): the host-enforced iteration ceiling shipped on
     * session.open. No workflow-layer carrier exists for orchestration
     * iteration limits today, so the platform default applies:
     * {@code myrmec.session.policy.max-iterations} (0 = unset → null on the
     * wire = no host-side limit).
     */
    @Value("${myrmec.session.policy.max-iterations:0}")
    private int policyMaxIterations;

    /**
     * §7.3 (Wave 6, A4): the host-enforced execution timeout shipped on
     * session.open. The step's declared timeoutSeconds (or the 300s platform
     * default) is threaded by the caller when known; 0 = unset → null.
     */
    @Value("${myrmec.session.policy.execution-timeout-seconds:0}")
    private int policyExecutionTimeoutSeconds;

    /** §7.3 (Wave 6, A4): METADATA-only capture is the platform default. */
    @Value("${myrmec.session.capture.level:METADATA}")
    private String captureLevel;
    @Value("${myrmec.session.capture.max-bytes:262144}")
    private int captureMaxBytes;

    /**
     * section 22.2: the effective interaction policy for orchestration
     * (WORKFLOW) sessions. Task 1 ships the InteractionProperties defaults —
     * the Team-policy configuration source is wired in Task 2 with the
     * persistence; until then every orchestration session receives exactly
     * the section 22.2 default policy (validation lives in
     * InteractionProperties; the block is REQUIRED on the wire for WORKFLOW).
     */
    /**
     * §22.2 (option B): the engine-internal 10-field interaction policy for
     * orchestration sessions, consumed by {@code ExecutionRegistry} and
     * persisted onto the execution row. The V2 wire emits only the 3-field
     * {@link #holdPolicy(String)} — the two are deliberately decoupled.
     */
    public ai.myrmec.engine.inference.execution.interaction.InteractionProperties.Policy
            effectiveInteractionPolicy(String kind) {
        if (!"WORKFLOW".equals(kind)) {
            return null;
        }
        // Team-policy tightening (Task 2) not yet wired; ship the §22.2 defaults.
        return ai.myrmec.engine.inference.execution.interaction.InteractionProperties.defaults();
    }

    /**
     * V2 wire {@code policy.interaction} — the 3-field hold policy that
     * replaces the legacy 10-field wire block (§22.2: allowHold / maxHoldSeconds
     * / onHoldTimeout). Null for conversation sessions.
     */
    private SessionOpenPayload.Policy.HoldPolicy holdPolicy(String kind) {
        if (!"WORKFLOW".equals(kind)) {
            return null;
        }
        return new SessionOpenPayload.Policy.HoldPolicy(true, 600, "RESUME");
    }

    /**
     * Assemble the V2 {@code session.open} payload for a CONVERSATION session,
     * building the full multi-model ensemble from the conversation's frozen
     * {@code assistant_agent_bindings} (keyed to {@code assistant_version_id}).
     *
     * <p>The conversation carries {@code assistant_version_id}; its bindings
     * were frozen at publish (including {@code agent_profile_version_id}), so
     * this is a pure read — no "latest published" re-resolution, hence no
     * drift. The PRIMARY binding becomes {@code profile.primaryModel} and each
     * SUB_AGENT binding becomes a {@code profile.subagents[]} entry.</p>
     */
    @Transactional
    public SessionOpenPayload assembleConversation(UUID sessionId, UUID conversationId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));

        UUID assistantVersionId = conversation.getAssistantVersionId();
        if (assistantVersionId == null) {
            throw new IllegalArgumentException("Conversation has no assistant version to resolve bindings");
        }
        List<AssistantAgentBinding> bindings = assistantAgentBindingRepository
                .findByAssistantVersionIdOrderByRoleTypeAsc(assistantVersionId);

        Ensemble ensemble = buildEnsemble(bindings, conversation.getTeamId());

        session.setContextPins(ensemble.contextPins());
        sessionRepository.save(session);

        return new SessionOpenPayload(
                sessionId,
                "CONVERSATION",
                conversation.getTeamId(),
                conversationId.toString(),
                null,
                null,
                ensemble.profile(),
                resolveWorkspace(conversation.getTeamId()),
                resolveConversationPolicy(ensemble),
                null,
                channelOffer(session),
                sealEnsembleCredentials(session, ensemble));
    }

    /**
     * Assemble the V2 {@code session.open} payload for a WORKFLOW (single-profile)
     * session. Workflow binds a single {@code agent_profile_id} (its own
     * bindings structure is the follow-up); for now this resolves the profile's
     * published version directly and ships the orchestration {@code task} via
     * the caller-installed assignment.
     */
    @Transactional
    public SessionOpenPayload assembleWorkflow(UUID sessionId, UUID refId, UUID teamId,
                                               UUID agentProfileId,
                                               int stepTimeoutSeconds, Integer stepMaxIterations,
                                               SessionOpenPayload.Task task) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        AgentProfile profile = agentProfileRepository.findById(agentProfileId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent profile not found: " + agentProfileId));
        AgentProfileVersion version = agentProfileVersionService.requirePublished(agentProfileId);

        ResolvedContext ctx = resolveContext(profile, version, teamId);
        session.setContextPins(ctx.contextPins());
        sessionRepository.save(session);

        SessionOpenPayload.AgentProfile profileBlock = new SessionOpenPayload.AgentProfile(
                version.getId(),
                profile.getName(),
                version.getSystemPrompt(),
                "default",
                Map.of("default", ctx.modelSpec()),
                ctx.toolGrants(),
                List.of(),
                List.of(),
                ctx.kbHandles(),
                null);

        return new SessionOpenPayload(
                sessionId,
                "WORKFLOW",
                teamId,
                refId == null ? null : refId.toString(),
                null,
                null,
                profileBlock,
                resolveWorkspace(teamId),
                resolveWorkflowPolicy(version, ctx, stepTimeoutSeconds, stepMaxIterations),
                task,
                channelOffer(session),
                requireDispatchableThenSeal(session, ctx));
    }

    /**
     * §22.3/D7 dedicated-transport offer. Every session (CONVERSATION and
     * WORKFLOW alike) carries the offer — the Agent SDK requires it at
     * {@code Agent.init} to connect the dedicated channel socket. The
     * configured endpoint + a serving host instance are still required
     * (an offer with no dialable endpoint would strand the session).
     */
    private SessionOpenPayload.ChannelOffer channelOffer(Session session) {
        String endpoint = channelEndpoint;
        if (endpoint == null || endpoint.isBlank()) {
            // Fall back to this replica's own address (host:port) so the
            // Agent SDK always receives a channel offer on session.open,
            // even when MYRMEC_CHANNEL_ENDPOINT is not set (dev phase).
            String self = nodeRegistryService.getSelfAddress();
            if (self == null || self.isBlank()) {
                log.debug("Channel offer skipped for session {} (no endpoint or node address)",
                        session.getId());
                return null;
            }
            endpoint = self;
        }
        String token = channelTokenService.mint(session.getId(), session.getHostInstanceId());
        String scheme = "ws".equalsIgnoreCase(channelScheme) ? "ws" : "wss";
        // WebSocketConfig registers the channel handler at
        // /api/v1/agent/ws (the same HOST_JWT handshake
        // interceptor as the control socket); the offer must point at the
        // REAL route or the dial lands on the security chain (403).
        String wsEndpoint = scheme + "://" + endpoint + "/api/v1/agent/ws";
        log.debug("Channel offer minted for session {} (endpoint {})",
                session.getId(), wsEndpoint);
        return new SessionOpenPayload.ChannelOffer(wsEndpoint, token);
    }

    /**
     * Intermediate context bundle for the single-profile (workflow) path.
     * Carries the resolved V2 building blocks plus the raw plaintexts that
     * must be sealed then dropped.
     */
    private record ResolvedContext(
            SessionOpenPayload.ModelSpec modelSpec,
            List<SessionOpenPayload.ToolGrant> toolGrants,
            List<SessionOpenPayload.KnowledgeSourceHandle> kbHandles,
            Map<String, Object> contextPins,
            Map<String, SessionOpenPayload.Policy.CommandTemplate> commandTemplates,
            String requiredIsolation,
            /** Raw plaintexts read from the secret store — sealed then dropped (design §10). */
            String modelApiKey,
            String workspaceRepoToken) {
    }

    /**
     * Intermediate bundle for the multi-model (conversation) path: the fully
     * built V2 {@code profile} block plus the per-model credential refs that
     * must be sealed, and the resolved command templates + isolation.
     */
    private record Ensemble(
            SessionOpenPayload.AgentProfile profile,
            Map<String, Object> contextPins,
            Map<String, SessionOpenPayload.Policy.CommandTemplate> commandTemplates,
            String requiredIsolation,
            Map<String, String> credentialRefToApiKey) {
    }

    private ResolvedContext resolveContext(AgentProfile profile,
                                            AgentProfileVersion version,
                                            UUID teamId) {
        String modelApiKey = modelService.getApiKey(version.getDefaultModel());
        SessionOpenPayload.ModelSpec modelSpec = resolveModelSpec(version.getDefaultModel(), modelApiKey);

        String workspaceRepoToken = resolveWorkspaceToken(teamId);
        List<SessionOpenPayload.ToolGrant> tools = resolveToolGrants(version, teamId);

        List<KnowledgeSource> activeSources = resolveActiveKnowledgeSources(teamId);
        List<SessionOpenPayload.KnowledgeSourceHandle> kbHandles = activeSources.stream()
                .map(s -> new SessionOpenPayload.KnowledgeSourceHandle(
                        s.getId(), s.getName(), s.getDescription()))
                .toList();

        Map<String, Object> contextPins = new HashMap<>();
        List<String> sourceIds = activeSources.stream()
                .map(KnowledgeSource::getId)
                .map(UUID::toString)
                .toList();
        contextPins.put("knowledgeSourceIds", sourceIds);
        List<UUID> instructionVersionIds = instructionAssetVersionResolver
                .resolveActiveVersionIdsForTeam(teamId);
        contextPins.put("instructionAssetVersionIds",
                instructionVersionIds.stream().map(UUID::toString).toList());

        Map<String, SessionOpenPayload.Policy.CommandTemplate> commandTemplates =
                resolveCommandTemplates(version, Set.of());

        return new ResolvedContext(modelSpec, tools, kbHandles, contextPins,
                commandTemplates,
                version.getRequiredIsolation() == null
                        ? "TRUSTED_PROCESS" : version.getRequiredIsolation().name(),
                modelApiKey, workspaceRepoToken);
    }

    /**
     * Build the full multi-model ensemble for a conversation from its frozen
     * {@code assistant_agent_bindings}. PRIMARY → {@code profile.primaryModel} +
     * a {@code models} map entry; each SUB_AGENT → a {@code subagents[]} entry.
     */
    private Ensemble buildEnsemble(List<AssistantAgentBinding> bindings, UUID teamId) {
        AssistantAgentBinding primary = bindings.stream()
                .filter(b -> b.getRoleType() == AssistantAgentBinding.RoleType.PRIMARY)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Assistant version has no PRIMARY agent binding"));

        Map<String, SessionOpenPayload.ModelSpec> models = new LinkedHashMap<>();
        Map<String, String> credentialRefToApiKey = new LinkedHashMap<>();
        List<SessionOpenPayload.SubagentSpec> subagents = new ArrayList<>();
        Set<String> referencedTemplates = new HashSet<>();

        // PRIMARY model
        String primaryModelCode = resolvePrimaryModelCode(primary);
        String primaryApiKey = modelService.getApiKey(primaryModelCode);
        SessionOpenPayload.ModelSpec primarySpec = resolveModelSpecForBinding(primary, primaryModelCode, primaryApiKey);
        String primaryRef = "model:" + primaryModelCode;
        models.put(primaryModelCode, primarySpec);
        credentialRefToApiKey.put(primaryRef, primaryApiKey);

        // Primary tools come from the pinned version of the primary profile.
        AgentProfileVersion primaryVersion = resolveVersion(primary);
        List<SessionOpenPayload.ToolGrant> primaryTools = resolveToolGrants(primaryVersion, teamId);
        referencedTemplates.addAll(collectReferencedTemplates(primaryTools));

        // SUB_AGENT bindings
        for (AssistantAgentBinding sub : bindings) {
            if (sub.getRoleType() != AssistantAgentBinding.RoleType.SUB_AGENT) {
                continue;
            }
            String modelCode = sub.getModelCode() != null
                    ? sub.getModelCode() : resolvePrimaryModelCode(sub);
            String subApiKey = modelService.getApiKey(modelCode);
            if (!models.containsKey(modelCode)) {
                models.put(modelCode, resolveModelSpecForBinding(sub, modelCode, subApiKey));
                credentialRefToApiKey.put("model:" + modelCode, subApiKey);
            }
            AgentProfileVersion subVersion = resolveVersion(sub);
            List<SessionOpenPayload.ToolGrant> subTools = resolveToolGrants(subVersion, teamId);
            referencedTemplates.addAll(collectReferencedTemplates(subTools));
            subagents.add(new SessionOpenPayload.SubagentSpec(
                    sub.getAgentAlias(),
                    sub.getAgentAlias(),
                    subVersion.getSystemPrompt(),
                    modelCode,
                    subTools,
                    null));
        }

        String systemPrompt = composeSystemPrompt(primaryVersion.getSystemPrompt(),
                primary.getPromptAddendum());

        List<KnowledgeSource> activeSources = resolveActiveKnowledgeSources(teamId);
        List<SessionOpenPayload.KnowledgeSourceHandle> kbHandles = activeSources.stream()
                .map(s -> new SessionOpenPayload.KnowledgeSourceHandle(
                        s.getId(), s.getName(), s.getDescription()))
                .toList();

        SessionOpenPayload.AgentProfile profile = new SessionOpenPayload.AgentProfile(
                primaryVersion.getId(),
                null,
                systemPrompt,
                primaryModelCode,
                models,
                primaryTools,
                subagents,
                List.of(),
                kbHandles,
                null);

        Map<String, Object> contextPins = new HashMap<>();
        contextPins.put("knowledgeSourceIds",
                activeSources.stream().map(s -> s.getId().toString()).toList());
        contextPins.put("instructionAssetVersionIds",
                instructionAssetVersionResolver.resolveActiveVersionIdsForTeam(teamId)
                        .stream().map(UUID::toString).toList());
        contextPins.put("agentProfileVersionId", primaryVersion.getId().toString());

        Map<String, SessionOpenPayload.Policy.CommandTemplate> commandTemplates =
                resolveCommandTemplates(primaryVersion, referencedTemplates);

        return new Ensemble(profile, contextPins, commandTemplates,
                primaryVersion.getRequiredIsolation() == null
                        ? "TRUSTED_PROCESS" : primaryVersion.getRequiredIsolation().name(),
                credentialRefToApiKey);
    }

    /**
     * Mode gate (§13 item 3) then seal: rejects the dispatch for a
     * GATEWAY-mode host before any credential sealing happens.
     */
    private List<SessionCredential> requireDispatchableThenSeal(Session session, ResolvedContext ctx) {
        requireDispatchableModelAccessMode(session);
        return sealCredentials(session.getId(), ctx);
    }

    /** Conversation policy: budget + command templates + isolation + capture + hold. */
    private SessionOpenPayload.Policy resolveConversationPolicy(Ensemble ensemble) {
        return new SessionOpenPayload.Policy(
                new SessionOpenPayload.Policy.Budget(null, null, null,
                        policyMaxIterations > 0 ? policyMaxIterations : null,
                        policyExecutionTimeoutSeconds > 0 ? policyExecutionTimeoutSeconds : null,
                        "FAIL"),
                ensemble.commandTemplates(),
                ensemble.requiredIsolation(),
                new SessionOpenPayload.Policy.CaptureSpec(captureLevel, captureMaxBytes),
                null);
    }

    /** Workflow policy: same shape, plus the step's declared timeout/iterations. */
    private SessionOpenPayload.Policy resolveWorkflowPolicy(
            AgentProfileVersion version, ResolvedContext ctx,
            int stepTimeoutSeconds, Integer stepMaxIterations) {
        Integer timeout = stepTimeoutSeconds > 0 ? stepTimeoutSeconds : null;
        Integer maxIter = stepMaxIterations != null && stepMaxIterations > 0
                ? stepMaxIterations
                : (policyMaxIterations > 0 ? policyMaxIterations : null);
        return new SessionOpenPayload.Policy(
                new SessionOpenPayload.Policy.Budget(null, null, null, maxIter, timeout, "FAIL"),
                ctx.commandTemplates(),
                ctx.requiredIsolation(),
                new SessionOpenPayload.Policy.CaptureSpec(captureLevel, captureMaxBytes),
                holdPolicy("WORKFLOW"));
    }

    /** Seal the multi-model ensemble's per-model API keys (conversation path). */
    private List<SessionCredential> sealEnsembleCredentials(Session session, Ensemble ensemble) {
        requireDispatchableModelAccessMode(session);
        if (ensemble.credentialRefToApiKey().isEmpty() && resolveWorkspaceToken(session.getTeamId()) == null) {
            return List.of();
        }
        Session s = sessionRepository.findById(session.getId()).orElse(null);
        if (s == null || s.getHostInstanceId() == null) {
            return List.of();
        }
        AgentHostInstance instance = agentHostInstanceRepository
                .findById(s.getHostInstanceId()).orElse(null);
        if (instance == null) {
            return List.of();
        }
        List<SessionCredential> credentials = new ArrayList<>();
        for (Map.Entry<String, String> e : ensemble.credentialRefToApiKey().entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            credentials.add(new SessionCredential(e.getKey(), PURPOSE_MODEL,
                    credentialEnvelopeService.buildEnvelope(instance.getId(),
                            instance.getAgentHostId(), session.getId(), PURPOSE_MODEL,
                            e.getValue())));
        }
        String repoToken = resolveWorkspaceToken(session.getTeamId());
        if (repoToken != null) {
            credentials.add(new SessionCredential(WORKSPACE_CREDENTIAL_REF, PURPOSE_WORKSPACE,
                    credentialEnvelopeService.buildEnvelope(instance.getId(),
                            instance.getAgentHostId(), session.getId(), PURPOSE_WORKSPACE,
                            repoToken)));
        }
        return credentials;
    }

    /**
     * Credential-envelope design §5.1 + §13 item 3: a host in GATEWAY mode
     * cannot be served yet — the model gateway is Phase 2 and does not
     * exist, so assembling a session for it would silently degrade to the
     * Direct path and break the governance guarantee. Fail the dispatch
     * loudly via the engine's standard dispatch-failure path instead.
     */
    private void requireDispatchableModelAccessMode(Session session) {
        if (session.getHostInstanceId() == null) {
            return; // legacy un-allocated session: mode gate not applicable
        }
        AgentHostInstance instance = agentHostInstanceRepository
                .findById(session.getHostInstanceId()).orElse(null);
        if (instance == null) {
            return; // instance already gone: existing session/instance close semantics handle it
        }
        ai.myrmec.engine.agent.AgentHost host = agentHostRepository
                .findById(instance.getAgentHostId()).orElse(null);
        if (host != null
                && host.getModelAccessMode() == ai.myrmec.engine.agent.ModelAccessMode.GATEWAY) {
            log.warn("Session {} dispatch refused: host {} is in GATEWAY model access mode, "
                            + "but the model gateway is not yet implemented (Phase 2) — "
                            + "failing the dispatch instead of silently falling back to Direct",
                    session.getId(), host.getId());
            throw new IllegalStateException(
                    "Dispatch refused: host " + host.getId() + " is in GATEWAY model access mode "
                            + "but the model gateway is not yet implemented (Phase 2)");
        }
    }

    /**
     * Seal the raw plaintexts carried by a resolved context into
     * {@code MyrmecSecureEnvelopeV1} entries for the session's serving
     * instance (design §8/§10). Plaintexts exist only inside this call.
     * Nulls are skipped (keyless model / no repo credential). Empty list
     * when the session needs no credentials at all.
     */
    private List<SessionCredential> sealCredentials(UUID sessionId, ResolvedContext ctx) {
        if (ctx.modelApiKey() == null && ctx.workspaceRepoToken() == null) {
            return List.of();
        }
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getHostInstanceId() == null) {
            log.warn("Session {} has no serving instance — cannot seal credentials; "
                    + "session.open will carry no credentials", sessionId);
            return List.of();
        }
        AgentHostInstance instance = agentHostInstanceRepository
                .findById(session.getHostInstanceId()).orElse(null);
        if (instance == null) {
            log.warn("Host instance {} not found — cannot seal credentials", session.getHostInstanceId());
            return List.of();
        }
        List<SessionCredential> credentials = new ArrayList<>();
        if (ctx.modelApiKey() != null) {
            credentials.add(new SessionCredential(MODEL_CREDENTIAL_REF, PURPOSE_MODEL,
                    credentialEnvelopeService.buildEnvelope(instance.getId(),
                            instance.getAgentHostId(), sessionId, PURPOSE_MODEL,
                            ctx.modelApiKey())));
        }
        if (ctx.workspaceRepoToken() != null) {
            credentials.add(new SessionCredential(WORKSPACE_CREDENTIAL_REF, PURPOSE_WORKSPACE,
                    credentialEnvelopeService.buildEnvelope(instance.getId(),
                            instance.getAgentHostId(), sessionId, PURPOSE_WORKSPACE,
                            ctx.workspaceRepoToken())));
        }
        return credentials;
    }

    private SessionOpenPayload.ModelSpec resolveModelSpec(String modelCode, String apiKey) {
        if (modelCode == null || modelCode.isBlank()) {
            throw new IllegalStateException("Agent profile has no model configured");
        }
        Model model = modelService.findByCode(modelCode);
        if (model == null) {
            throw new IllegalStateException("Model '" + modelCode + "' not found");
        }
        String endpoint = model.getApiEndpoint();
        if (endpoint == null && model.getProviderConfig() != null) {
            endpoint = model.getProviderConfig().getBaseUrl();
        }
        // credentialRef is only set when an API key is actually delivered —
        // the SDK rejects a declared ref with no matching credential in
        // credentials[].
        String credentialRef = apiKey == null ? null : "model:" + modelCode;
        return new SessionOpenPayload.ModelSpec(
                model.getProviderConfig() != null ? model.getProviderConfig().getCode() : "unknown",
                model.getModelId(),
                model.getName(),
                endpoint,
                credentialRef,
                model.getDefaultParams() != null ? model.getDefaultParams() : Map.of());
    }

    /** The model code a binding runs: its explicit override, else the profile's default. */
    private String resolvePrimaryModelCode(AssistantAgentBinding binding) {
        if (binding.getModelCode() != null && !binding.getModelCode().isBlank()) {
            return binding.getModelCode();
        }
        AgentProfileVersion version = resolveVersion(binding);
        if (version.getDefaultModel() == null || version.getDefaultModel().isBlank()) {
            throw new IllegalStateException("Binding has no model (neither override nor profile default)");
        }
        return version.getDefaultModel();
    }

    private SessionOpenPayload.ModelSpec resolveModelSpecForBinding(
            AssistantAgentBinding binding, String modelCode, String apiKey) {
        return resolveModelSpec(modelCode, apiKey);
    }

    /** Resolve a binding's frozen profile version (pin), else latest published. */
    private AgentProfileVersion resolveVersion(AssistantAgentBinding binding) {
        if (binding.getAgentProfileVersionId() != null) {
            return agentProfileVersionRepository.findByIdWithTools(binding.getAgentProfileVersionId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Pinned agent profile version not found: "
                                    + binding.getAgentProfileVersionId()));
        }
        return agentProfileVersionService.requirePublished(binding.getAgentProfileId());
    }

    /** Compose the effective primary system prompt (profile + binding addendum). */
    private String composeSystemPrompt(String basePrompt, String addendum) {
        if (addendum == null || addendum.isBlank()) {
            return basePrompt;
        }
        if (basePrompt == null || basePrompt.isBlank()) {
            return addendum;
        }
        return basePrompt + "\n\n" + addendum;
    }

    /** Read the workspace repo token for sealing (design §9); null when none. */
    private String resolveWorkspaceToken(UUID teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null || team.getWorkspaceCredentialSecretId() == null) {
            return null;
        }
        try {
            SecretPayload payload = secretResolverService.resolve(
                    team.getWorkspaceCredentialSecretId(), teamId);
            return extractToken(payload);
        } catch (Exception e) {
            log.warn("Failed to resolve workspace credential for team {}: {}",
                    teamId, e.getMessage());
            return null;
        }
    }

    /** V2 discriminated workspace union: none | git | local. */
    private SessionOpenPayload.Workspace resolveWorkspace(UUID teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null || team.getWorkspaceRepoUrl() == null
                || team.getWorkspaceRepoUrl().isBlank()) {
            return new SessionOpenPayload.Workspace("none", null, null, null, null);
        }
        String branch = team.getWorkspaceRepoBranch() != null
                ? team.getWorkspaceRepoBranch() : "main";
        String repoToken = resolveWorkspaceToken(teamId);
        SessionOpenPayload.Workspace.GitSource source = new SessionOpenPayload.Workspace.GitSource(
                team.getWorkspaceRepoUrl(),
                branch,
                null,
                branch,
                repoToken != null ? WORKSPACE_CREDENTIAL_REF : null);
        return new SessionOpenPayload.Workspace("git", source, null, null, null);
    }

    /** V2 tool grants: name + resolved approval + allowed command-template names. */
    private List<SessionOpenPayload.ToolGrant> resolveToolGrants(
            AgentProfileVersion version, UUID teamId) {
        var profileTools = version.getTools();
        boolean autoHitl = teamRepository.findById(teamId)
                .map(Team::isAutoHitlOnDestructive)
                .orElse(false);

        if (profileTools == null || profileTools.isEmpty()) {
            return List.of();
        }
        return profileTools.stream()
                .filter(t -> t.getStatus() == ai.myrmec.engine.tool.ToolStatus.ACTIVE)
                .map(t -> {
                    String approval = resolveApproval(t, autoHitl);
                    List<String> allowedCommands = List.of();
                    if (t.getCode().equals("execute_command")) {
                        // SDK requires execute_command to carry at least one
                        // allowedCommands template; skip the grant entirely
                        // when the profile defines none.
                        allowedCommands = resolveAllowedCommands(version, t);
                        if (allowedCommands.isEmpty()) {
                            return null;
                        }
                    }
                    return new SessionOpenPayload.ToolGrant(
                            t.getCode(), approval, allowedCommands);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** Approval mode: REQUIRED for destructive/irreversible tools under auto-HITL. */
    private String resolveApproval(Tool tool, boolean autoHitl) {
        if (!autoHitl) {
            return "NONE";
        }
        ai.myrmec.engine.tool.RiskClass rc = tool.getRiskClass();
        if (rc == ai.myrmec.engine.tool.RiskClass.DESTRUCTIVE
                || rc == ai.myrmec.engine.tool.RiskClass.IRREVERSIBLE) {
            return "REQUIRED";
        }
        return "NONE";
    }

    /** Command-template names the execute_command tool is allowed to invoke. */
    private List<String> resolveAllowedCommands(AgentProfileVersion version, Tool tool) {
        // The profile's command_templates define the allowed set; expose all
        // template names (the referenced-subset refinement happens on the
        // orchestration path via OrchestrationAssignmentAssembler).
        Map<String, Object> templates = parseJsonMap(version.getCommandTemplates());
        return new ArrayList<>(templates.keySet());
    }

    /** Collect every template name referenced by a tool's allowedCommands. */
    private Set<String> collectReferencedTemplates(List<SessionOpenPayload.ToolGrant> grants) {
        Set<String> names = new HashSet<>();
        for (SessionOpenPayload.ToolGrant g : grants) {
            names.addAll(g.allowedCommands());
        }
        return names;
    }

    /**
     * Parse the version's {@code command_templates} JSON into the V2
     * {@code CommandTemplate} records, optionally restricted to the
     * {@code referencedNames} subset (empty set = all).
     */
    private Map<String, SessionOpenPayload.Policy.CommandTemplate> resolveCommandTemplates(
            AgentProfileVersion version, Set<String> referencedNames) {
        Map<String, Object> all = parseJsonMap(version.getCommandTemplates());
        Map<String, SessionOpenPayload.Policy.CommandTemplate> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : all.entrySet()) {
            if (!referencedNames.isEmpty() && !referencedNames.contains(e.getKey())) {
                continue;
            }
            out.put(e.getKey(), toCommandTemplate(e.getValue()));
        }
        return out;
    }

    private SessionOpenPayload.Policy.CommandTemplate toCommandTemplate(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return null;
        }
        String executable = str(m.get("executable"));
        List<Object> args = m.get("args") instanceof List<?> l
                ? List.copyOf(l) : List.of();
        Map<String, SessionOpenPayload.Policy.CommandTemplate.Parameter> parameters =
                parseParameters(m.get("parameters"));
        String cwdPattern = str(m.get("cwdPattern"), "^\\\\.$");
        List<String> env = m.get("environmentAllowlist") instanceof List<?> el
                ? el.stream().map(String::valueOf).toList() : List.of("PATH", "CI");
        Integer timeout = m.get("timeoutSeconds") instanceof Number n ? n.intValue() : 180;
        Integer maxOutput = m.get("maxOutputBytes") instanceof Number n ? n.intValue() : 262144;
        String network = "ALLOW".equalsIgnoreCase(str(m.get("network"))) ? "ALLOW" : "DENY";
        Integer maxCpu = m.get("maxCpuSeconds") instanceof Number n ? n.intValue() : null;
        Integer maxMem = m.get("maxMemoryBytes") instanceof Number n ? n.intValue() : null;
        return new SessionOpenPayload.Policy.CommandTemplate(
                executable, args, parameters, cwdPattern, env, timeout, maxOutput,
                network, maxCpu, maxMem);
    }

    private Map<String, SessionOpenPayload.Policy.CommandTemplate.Parameter> parseParameters(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, SessionOpenPayload.Policy.CommandTemplate.Parameter> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> pm)) {
                continue;
            }
            String type = str(pm.get("type"), "string");
            boolean required = !Boolean.FALSE.equals(pm.get("required"));
            String pattern = str(pm.get("pattern"));
            List<Object> allowed = pm.get("allowedValues") instanceof List<?> al
                    ? List.copyOf(al) : List.of();
            out.put(String.valueOf(e.getKey()),
                    new SessionOpenPayload.Policy.CommandTemplate.Parameter(
                            type, required, pattern, allowed));
        }
        return out;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse command_templates JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : String.valueOf(o);
    }

    private List<KnowledgeSource> resolveActiveKnowledgeSources(UUID teamId) {
        List<KnowledgeSource> sources = new ArrayList<>();
        // Org-scoped
        sources.addAll(knowledgeSourceRepository.findByScopeAndTeamIdIsNull(Scope.ORGANIZATION));
        // Team-scoped
        sources.addAll(knowledgeSourceRepository.findByScopeAndTeamId(Scope.TEAM, teamId));
        // Filter to ACTIVE only
        return sources.stream()
                .filter(s -> EntityStatus.ACTIVE.equals(s.getStatus()))
                .toList();
    }

    /**
     * Return the IDs of active knowledge sources for the Team.
     * Public so {@link ai.myrmec.engine.conversation.ConversationService} can
     * build a {@link ai.myrmec.engine.context.ContextSnapshot} at creation time.
     */
    public List<UUID> resolveActiveKnowledgeSourceIds(UUID teamId) {
        return resolveActiveKnowledgeSources(teamId).stream()
                .map(KnowledgeSource::getId)
                .toList();
    }

    private String extractToken(SecretPayload payload) {
        if (payload instanceof SecretPayload.BearerToken bt) return bt.token();
        if (payload instanceof SecretPayload.ApiKey ak) return ak.key();
        if (payload instanceof SecretPayload.SecretKey sk) return sk.secret();
        if (payload instanceof SecretPayload.UsernamePassword up) return up.password();
        return null;
    }

    /**
     * Close a session (conversation unbind / execution end).
     */
    @Transactional
    public void closeSession(UUID sessionId) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && EntityStatus.ACTIVE.equals(session.getStatus())) {
            session.setStatus("CLOSED");
            session.setClosedAt(java.time.Instant.now());
            sessionRepository.save(session);
            log.info("Closed session {} for {} refId={}", sessionId, session.getServiceType(), session.getRefId());
        }
    }

    /**
     * Close all active sessions for a given ref_id (conversation_id or
     * workflow_request_id) and service type. Called when a conversation is
     * archived to clean up the multi-turn session that was kept open. Stale
     * duplicates from crashed reconnects are closed too - every ACTIVE row.
     */
    @Transactional
    public void closeSessionsByRefId(UUID refId, String serviceType) {
        sessionRepository.findByRefIdAndServiceType(refId, serviceType)
                .stream()
                .filter(s -> EntityStatus.ACTIVE.equals(s.getStatus()))
                .forEach(session -> {
                    session.setStatus("CLOSED");
                    session.setClosedAt(java.time.Instant.now());
                    sessionRepository.save(session);
                    log.info("Closed session {} for {} refId={} (bulk close on archive)",
                            session.getId(), serviceType, refId);
                });
    }

    /**
     * Find an active session by ref_id (conversation_id or workflow_request_id).
     * With stale duplicates possible, the newest ACTIVE row wins (first in the
     * repository's default order when present; callers pass a stable id, so
     * any ACTIVE row among duplicates is the current session).
     */
    @Transactional(readOnly = true)
    public Session findActiveSession(UUID refId, String serviceType) {
        return sessionRepository.findByRefIdAndServiceType(refId, serviceType)
                .stream()
                .filter(s -> EntityStatus.ACTIVE.equals(s.getStatus()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Check if a knowledge source ID is pinned to the given session.
     */
    @Transactional(readOnly = true)
    public boolean isKnowledgeSourcePinned(UUID sessionId, UUID knowledgeSourceId) {
        Session session = sessionRepository.findByIdAndStatus(sessionId, EntityStatus.ACTIVE).orElse(null);
        if (session == null || session.getContextPins() == null) return false;
        Object sourceIds = session.getContextPins().get("knowledgeSourceIds");
        if (sourceIds instanceof List<?> list) {
            return list.stream().anyMatch(id -> knowledgeSourceId.toString().equals(id));
        }
        return false;
    }
}
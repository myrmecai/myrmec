// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.agent.AgentProfileVersionService;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.context.InstructionAssetVersionResolver;
import ai.myrmec.engine.inference.security.CredentialEnvelopeService;
import ai.myrmec.engine.knowledge.KnowledgeSource;
import ai.myrmec.engine.knowledge.KnowledgeSourceRepository;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.secret.SecretPayload;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.tool.Tool;
import ai.myrmec.engine.websocket.message.payload.SessionCredential;
import ai.myrmec.engine.websocket.message.payload.SessionOpenPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final ProjectRepository projectRepository;
    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final AgentProfileVersionService agentProfileVersionService;
    private final SecretResolverService secretResolverService;
    private final InstructionAssetVersionResolver instructionAssetVersionResolver;
    private final SessionRepository sessionRepository;
    private final AgentHostInstanceRepository agentHostInstanceRepository;
    private final ai.myrmec.engine.agent.AgentHostRepository agentHostRepository;
    private final CredentialEnvelopeService credentialEnvelopeService;

    /** credentialRef constants (design §9) — stable across both config blocks. */
    private static final String MODEL_CREDENTIAL_REF = "model-provider-token";
    private static final String WORKSPACE_CREDENTIAL_REF = "repo-token";
    private static final String PURPOSE_MODEL = "MODEL_PROVIDER";
    private static final String PURPOSE_WORKSPACE = "WORKSPACE_TOKEN";

    /**
     * Assemble the session.open payload and persist the session row.
     *
     * @param kind              "CONVERSATION" or "WORKFLOW" (§21.4 wire field)
     * @param refId             conversation_id or workflow_request_id
     * @param projectId         project scope
     * @param agentProfileId    agent profile (for model + system prompt resolution)
     * @return the assembled SessionOpenPayload (ready to send over WebSocket)
     */
    @Transactional
    public SessionOpenPayload assemble(String kind, UUID refId, UUID projectId,
                                        UUID agentProfileId) {
        // 1. Resolve agent profile and its PUBLISHED version (design
        //    §16.1: the behaviour contract lives on the version row).
        AgentProfile profile = agentProfileRepository.findById(agentProfileId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent profile not found: " + agentProfileId));
        AgentProfileVersion version = agentProfileVersionService.requirePublished(agentProfileId);

        // 2-6. Resolve model, workspace, tools, knowledge, HITL policy, and pins.
        ResolvedContext ctx = resolveContext(profile, version, projectId);

        // 7. Create session row
        Session session = new Session();
        session.setServiceType(kind);
        session.setRefId(refId);
        session.setProjectId(projectId);
        session.setContextPins(ctx.contextPins());
        session.setStatus(EntityStatus.ACTIVE);
        session = sessionRepository.save(session);

        log.info("Created session {} for {} refId={}", session.getId(), kind, refId);

        // §9.5 / §16.1: the pin is the real published version row ID —
        // a worker bound to this session replays against exactly this
        // content even if the profile publishes a newer version later.
        return new SessionOpenPayload(
                session.getId(),
                kind,
                projectId,
                version.getId(),
                ctx.modelConfig(),
                ctx.workspace(),
                ctx.tools(),
                ctx.kbHandles(),
                ctx.autoHitl(),
                dispatchContextExecutionMode(kind),
                null,
                null,
                null,
                requireDispatchableThenSeal(session, ctx));
    }

    /**
     * Assemble the full execution context WITHOUT creating a session row —
     * the unified protocol's allocator owns the row (§7.1: created at
     * offer). The legacy assemble(...) keeps creating rows for the legacy
     * dispatch path until cutover. Pins context onto the given session id.
     */
    @Transactional
    public SessionOpenPayload assembleContext(UUID sessionId, String kind, UUID refId,
                                              UUID projectId, UUID agentProfileId) {
        AgentProfile profile = agentProfileRepository.findById(agentProfileId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent profile not found: " + agentProfileId));
        AgentProfileVersion version = agentProfileVersionService.requirePublished(agentProfileId);

        ResolvedContext ctx = resolveContext(profile, version, projectId);

        // Install the pins on the EXISTING allocator-owned row.
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        session.setContextPins(ctx.contextPins());
        sessionRepository.save(session);

        return new SessionOpenPayload(
                sessionId,
                kind,
                projectId,
                version.getId(),
                ctx.modelConfig(),
                ctx.workspace(),
                ctx.tools(),
                ctx.kbHandles(),
                ctx.autoHitl(),
                dispatchContextExecutionMode(kind),
                null,
                null,
                null,
                requireDispatchableThenSeal(session, ctx));
    }

    /**
     * §7.3 dispatch-context population (§21.4): the executionMode rides the
     * session.open payload — "ORCHESTRATION" when the session belongs to an
     * orchestration-kind workflow execution, null for conversations.
     */
    private String dispatchContextExecutionMode(String kind) {
        return "WORKFLOW".equals(kind) ? "ORCHESTRATION" : null;
    }

    /**
     * Intermediate context bundle used by both {@link #assemble(String, UUID, UUID, UUID)}
     * and {@link #assembleContext(UUID, String, UUID, UUID, UUID)}. Keeps the
     * legacy row-creating path and the allocator path identical in what they
     * resolve, while only the persistence step differs.
     */
    private record ResolvedContext(
            SessionOpenPayload.ModelConfig modelConfig,
            SessionOpenPayload.WorkspaceConfig workspace,
            List<SessionOpenPayload.ToolDefinition> tools,
            List<SessionOpenPayload.KnowledgeSourceHandle> kbHandles,
            boolean autoHitl,
            Map<String, Object> contextPins,
            /** Raw plaintexts read from the secret store — sealed then dropped (design §10). */
            String modelApiKey,
            String workspaceRepoToken) {
    }

    /** A pending (ref, purpose, plaintext) triple awaiting sealing. */
    private record PendingCredential(String credentialRef, String purpose, String plaintext) {
    }

    private ResolvedContext resolveContext(AgentProfile profile,
                                            AgentProfileVersion version,
                                            UUID projectId) {
        // 2. Resolve model (read the API key once per session for sealing)
        String modelApiKey = modelService.getApiKey(version.getDefaultModel());
        SessionOpenPayload.ModelConfig modelConfig =
                resolveModel(profile, version, modelApiKey);

        // 3. Resolve workspace (nullable); read the repo token for sealing
        String workspaceRepoToken = resolveWorkspaceToken(projectId);
        SessionOpenPayload.WorkspaceConfig workspace = resolveWorkspace(projectId);

        // 4. Resolve tool catalog (authorized set — filtered by the version)
        List<SessionOpenPayload.ToolDefinition> tools = resolveTools(profile.getId(), version);

        // 5. Resolve knowledge-source handles (pinned KB catalog — handles only)
        List<KnowledgeSource> activeSources = resolveActiveKnowledgeSources(projectId);
        List<SessionOpenPayload.KnowledgeSourceHandle> kbHandles = activeSources.stream()
                .map(s -> new SessionOpenPayload.KnowledgeSourceHandle(
                        s.getId(), s.getName(), s.getDescription()))
                .toList();

        // 6. Resolve project HITL policy
        boolean autoHitl = projectRepository.findById(projectId)
                .map(ai.myrmec.engine.project.Project::isAutoHitlOnDestructive)
                .orElse(false);

        // 6. Pin instruction asset versions + knowledge source IDs
        Map<String, Object> contextPins = new HashMap<>();
        // Knowledge source IDs
        List<String> sourceIds = activeSources.stream()
                .map(KnowledgeSource::getId)
                .map(UUID::toString)
                .toList();
        contextPins.put("knowledgeSourceIds", sourceIds);
        // Instruction asset version pinning — resolve published version IDs for the project scope
        List<UUID> instructionVersionIds = instructionAssetVersionResolver
                .resolveActiveVersionIdsForProject(projectId);
        contextPins.put("instructionAssetVersionIds",
                instructionVersionIds.stream().map(UUID::toString).toList());

        return new ResolvedContext(modelConfig, workspace, tools, kbHandles, autoHitl,
                contextPins, modelApiKey, workspaceRepoToken);
    }

    /**
     * Mode gate (§13 item 3) then seal: rejects the dispatch for a
     * GATEWAY-mode host before any credential sealing happens.
     */
    private List<SessionCredential> requireDispatchableThenSeal(Session session, ResolvedContext ctx) {
        requireDispatchableModelAccessMode(session);
        return sealCredentials(session.getId(), ctx);
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

    private SessionOpenPayload.ModelConfig resolveModel(AgentProfile profile,
            AgentProfileVersion version, String apiKey) {
        if (version.getDefaultModel() == null || version.getDefaultModel().isBlank()) {
            throw new IllegalStateException(
                    "Agent profile " + profile.getName() + " has no default model configured");
        }
        Model model = modelService.findByCode(version.getDefaultModel());
        if (model == null) {
            throw new IllegalStateException(
                    "Model '" + version.getDefaultModel() + "' not found for profile '"
                            + profile.getName() + "'");
        }
        // endpoint (§21.4): explicit model endpoint, else provider baseUrl.
        String endpoint = model.getApiEndpoint();
        if (endpoint == null && model.getProviderConfig() != null) {
            endpoint = model.getProviderConfig().getBaseUrl();
        }
        // Credential delivery (design §9): the wire carries a credentialRef;
        // the key itself travels only inside the sealed envelope.
        return new SessionOpenPayload.ModelConfig(
                model.getProviderConfig() != null ? model.getProviderConfig().getCode() : "unknown",
                model.getModelId(),
                endpoint,
                apiKey != null ? MODEL_CREDENTIAL_REF : null,
                model.getDefaultParams() != null ? model.getDefaultParams() : Map.of());
    }

    /** Read the workspace repo token for sealing (design §9); null when none. */
    private String resolveWorkspaceToken(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getWorkspaceCredentialSecretId() == null) {
            return null;
        }
        try {
            SecretPayload payload = secretResolverService.resolve(
                    project.getWorkspaceCredentialSecretId(), projectId);
            return extractToken(payload);
        } catch (Exception e) {
            log.warn("Failed to resolve workspace credential for project {}: {}",
                    projectId, e.getMessage());
            return null;
        }
    }

    private SessionOpenPayload.WorkspaceConfig resolveWorkspace(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getWorkspaceRepoUrl() == null
                || project.getWorkspaceRepoUrl().isBlank()) {
            return null;
        }
        String branch = project.getWorkspaceRepoBranch() != null
                ? project.getWorkspaceRepoBranch() : "main";
        String repoToken = resolveWorkspaceToken(projectId);
        return new SessionOpenPayload.WorkspaceConfig(
                project.getWorkspaceRepoUrl(),
                branch,
                null,  // subPath — not stored on Project today
                repoToken != null ? WORKSPACE_CREDENTIAL_REF : null);
    }

    private List<SessionOpenPayload.ToolDefinition> resolveTools(
            UUID profileId, AgentProfileVersion version) {
        var profileTools = version.getTools();
        log.info("Resolving tools for profile {}: {} tools from published version",
                profileId, profileTools != null ? profileTools.size() : 0);

        if (profileTools != null && !profileTools.isEmpty()) {
            var tools = profileTools.stream()
                    .filter(t -> t.getStatus() == ai.myrmec.engine.tool.ToolStatus.ACTIVE)
                    .map(t -> new SessionOpenPayload.ToolDefinition(
                            t.getCode(),
                            t.getDescription(),
                            t.getConfigSchema(),
                            t.getRiskClass() != null ? t.getRiskClass().name() : "SAFE"))
                    .toList();
            log.info("Resolved {} tools from profile {}", tools.size(), profileId);
            return tools;
        }

        // No tools configured on the version — return an empty list.
        // A profile without tools should not inherit all system tools.
        log.info("Profile {} has no tools configured — returning empty list", profileId);
        return List.of();
    }

    private List<KnowledgeSource> resolveActiveKnowledgeSources(UUID projectId) {
        List<KnowledgeSource> sources = new ArrayList<>();
        // Org-scoped
        sources.addAll(knowledgeSourceRepository.findByScopeAndProjectIdIsNull(Scope.ORGANIZATION));
        // Project-scoped
        sources.addAll(knowledgeSourceRepository.findByScopeAndProjectId(Scope.PROJECT, projectId));
        // Filter to ACTIVE only
        return sources.stream()
                .filter(s -> EntityStatus.ACTIVE.equals(s.getStatus()))
                .toList();
    }

    /**
     * Return the IDs of active knowledge sources for the project.
     * Public so {@link ai.myrmec.engine.conversation.ConversationService} can
     * build a {@link ai.myrmec.engine.context.ContextSnapshot} at creation time.
     */
    public List<UUID> resolveActiveKnowledgeSourceIds(UUID projectId) {
        return resolveActiveKnowledgeSources(projectId).stream()
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
     * archived to clean up the multi-turn session that was kept open.
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
     */
    @Transactional(readOnly = true)
    public Session findActiveSession(UUID refId, String serviceType) {
        return sessionRepository.findByRefIdAndServiceType(refId, serviceType)
                .filter(s -> EntityStatus.ACTIVE.equals(s.getStatus()))
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
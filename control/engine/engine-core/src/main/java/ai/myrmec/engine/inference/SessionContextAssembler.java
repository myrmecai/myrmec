// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.agent.AgentProfileVersionService;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.context.InstructionAssetVersionResolver;
import ai.myrmec.engine.knowledge.KnowledgeSource;
import ai.myrmec.engine.knowledge.KnowledgeSourceRepository;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.secret.SecretPayload;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.tool.Tool;
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

    /**
     * Assemble the session.open payload and persist the session row.
     *
     * @param serviceType       "CONVERSATION" or "WORKFLOW"
     * @param refId             conversation_id or workflow_request_id
     * @param projectId         project scope
     * @param agentProfileId    agent profile (for model + system prompt resolution)
     * @return the assembled SessionOpenPayload (ready to send over WebSocket)
     */
    @Transactional
    public SessionOpenPayload assemble(String serviceType, UUID refId, UUID projectId,
                                        UUID agentProfileId) {
        // 1. Resolve agent profile and its PUBLISHED version (design
        //    §16.1: the behaviour contract lives on the version row).
        AgentProfile profile = agentProfileRepository.findById(agentProfileId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent profile not found: " + agentProfileId));
        AgentProfileVersion version = agentProfileVersionService.requirePublished(agentProfileId);

        // 2. Resolve model (decrypt API key once per session)
        SessionOpenPayload.ModelConfig modelConfig = resolveModel(profile, version);

        // 3. Resolve workspace (nullable)
        SessionOpenPayload.WorkspaceConfig workspace = resolveWorkspace(projectId);

        // 4. Resolve tool catalog (authorized set — filtered by the version)
        List<SessionOpenPayload.ToolDefinition> tools = resolveTools(agentProfileId, version);

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

        // 7. Create session row
        Session session = new Session();
        session.setServiceType(serviceType);
        session.setRefId(refId);
        session.setProjectId(projectId);
        session.setContextPins(contextPins);
        session.setStatus(EntityStatus.ACTIVE);
        session = sessionRepository.save(session);

        log.info("Created session {} for {} refId={}", session.getId(), serviceType, refId);

        // §9.5 / §16.1: the pin is the real published version row ID —
        // a worker bound to this session replays against exactly this
        // content even if the profile publishes a newer version later.
        return new SessionOpenPayload(
                session.getId(),
                serviceType,
                projectId,
                version.getId(),
                modelConfig,
                workspace,
                tools,
                kbHandles,
                autoHitl);
    }

    private SessionOpenPayload.ModelConfig resolveModel(AgentProfile profile, AgentProfileVersion version) {
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
        String apiKey = modelService.getApiKey(version.getDefaultModel());
        // Fall back to provider config's baseUrl when model has no explicit endpoint
        String apiEndpoint = model.getApiEndpoint();
        if (apiEndpoint == null && model.getProviderConfig() != null) {
            apiEndpoint = model.getProviderConfig().getBaseUrl();
        }
        return new SessionOpenPayload.ModelConfig(
                model.getProviderConfig() != null ? model.getProviderConfig().getCode() : "unknown",
                model.getModelId(),
                apiEndpoint,
                apiKey,
                model.getDefaultParams() != null ? model.getDefaultParams() : Map.of());
    }

    private SessionOpenPayload.WorkspaceConfig resolveWorkspace(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getWorkspaceRepoUrl() == null
                || project.getWorkspaceRepoUrl().isBlank()) {
            return null;
        }
        String branch = project.getWorkspaceRepoBranch() != null
                ? project.getWorkspaceRepoBranch() : "main";
        String repoToken = null;
        if (project.getWorkspaceCredentialSecretId() != null) {
            try {
                SecretPayload payload = secretResolverService.resolve(
                        project.getWorkspaceCredentialSecretId(), projectId);
                repoToken = extractToken(payload);
            } catch (Exception e) {
                log.warn("Failed to resolve workspace credential for project {}: {}",
                        projectId, e.getMessage());
            }
        }
        return new SessionOpenPayload.WorkspaceConfig(
                project.getWorkspaceRepoUrl(),
                branch,
                null,  // subPath — not stored on Project today
                repoToken);
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
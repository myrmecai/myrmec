package ai.myrmec.engine.conversation.dispatch;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.model.Model;
import ai.myrmec.engine.model.ModelService;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.websocket.message.payload.ConversationTurnAssignPayload;
import ai.myrmec.engine.websocket.message.payload.TaskAssignPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Routes a freshly-arrived USER message to an idle agent instance as a
 * {@code conversation.turn.assign} frame (Phase 6d).
 *
 * <p>This dispatcher deliberately bypasses {@code WorkflowTask} and the
 * scheduled {@code TaskDispatcherService} poller: a chat turn is not a
 * workflow step, has no retry semantics, and needs to land within
 * sub-second latency for streaming to feel responsive. Polling adds
 * 2&nbsp;seconds of dead time on average and would force conversational
 * turns into a foreign data model (workflow / step / attempt) that
 * doesn't fit them.</p>
 *
 * <p>Context assembly here is intentionally simple for Phase 6d
 * (sliding window of the most-recent {@value #HISTORY_LIMIT} messages,
 * pinned facts pass-through, no summarisation). A summariser running
 * against a cheap-model handle is the natural Phase 6d follow-up but
 * shipping that without first proving the end-to-end dispatch path is
 * premature.</p>
 *
 * <p><b>Best-effort.</b> When no idle agent instance is available the
 * dispatcher logs a warning and returns {@code false} \u2014 it does not
 * queue. The conversation row still carries the USER message, and a
 * later turn (or an agent coming online) will pick the thread back up
 * once we add a backlog drainer. Failing loud here is the right call:
 * the user-WS replay surface (Phase 6c-2) shows them the un-answered
 * USER row.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationTurnDispatcher {

    /**
     * How many recent messages to ship as context (oldest first). Chosen
     * small for Phase 6d so token cost stays bounded; the future
     * summariser pass collapses anything older into a single SYSTEM
     * entry.
     */
    public static final int HISTORY_LIMIT = 20;

    /** Per-turn timeout shipped to the agent. Mirrors workflow tasks. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final AgentRepository agentRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final AgentInstanceRepository agentInstanceRepository;
    private final AgentConnectionManager connectionManager;
    private final AgentWebSocketHandler webSocketHandler;
    private final ModelService modelService;
    private final ai.myrmec.engine.snapshot.SnapshotWriter snapshotWriter;

    /**
     * Assemble + dispatch one turn. Returns {@code true} if a frame was
     * actually sent to an agent.
     */
    public boolean dispatch(UUID conversationId) {
        Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
        if (convOpt.isEmpty()) {
            log.warn("Cannot dispatch turn \u2014 conversation {} not found", conversationId);
            return false;
        }
        Conversation conversation = convOpt.get();

        if (conversation.getAgentId() == null) {
            log.warn("Cannot dispatch turn \u2014 conversation {} has no agentId pinned", conversationId);
            return false;
        }

        Optional<Agent> agentOpt = agentRepository.findById(conversation.getAgentId());
        if (agentOpt.isEmpty()) {
            log.warn("Cannot dispatch turn \u2014 agent {} not found for conv {}",
                    conversation.getAgentId(), conversationId);
            return false;
        }
        Agent agent = agentOpt.get();

        Optional<AgentProfile> profileOpt = agentProfileRepository.findById(agent.getProfileId());
        if (profileOpt.isEmpty()) {
            log.warn("Cannot dispatch turn \u2014 profile {} not found for conv {}",
                    agent.getProfileId(), conversationId);
            return false;
        }
        AgentProfile profile = profileOpt.get();

        AgentInstance idleInstance = findIdleInstance(agent.getId()).orElse(null);
        if (idleInstance == null) {
            log.warn("No idle agent instance available for agent {} (conv {})",
                    agent.getId(), conversationId);
            return false;
        }

        List<ConversationMessage> all = conversationService.listMessages(conversationId);
        List<ConversationTurnAssignPayload.HistoryEntry> history = buildSlidingWindow(all);
        String userMessage = lastUserContent(all).orElse("");
        long assistantSequenceNo = all.isEmpty()
                ? 0L
                : all.get(all.size() - 1).getSequenceNo() + 1;

        String systemPrompt = conversation.getSystemPromptOverride() != null
                ? conversation.getSystemPromptOverride()
                : profile.getSystemPrompt();

        TaskAssignPayload.ModelInfo modelInfo = resolveModel(profile);

        ConversationTurnAssignPayload payload = ConversationTurnAssignPayload.builder()
                .conversationId(conversationId)
                .projectId(conversation.getProjectId())
                .agentId(agent.getId())
                .assistantSequenceNo(assistantSequenceNo)
                .systemPrompt(systemPrompt)
                .pinnedFacts(conversation.getPinnedFacts())
                .history(history)
                .userMessage(userMessage)
                .timeoutSeconds(DEFAULT_TIMEOUT_SECONDS)
                .model(modelInfo)
                .build();

        boolean sent = webSocketHandler.sendConversationTurn(idleInstance.getId(), payload);
        if (sent) {
            log.info("Dispatched conversation turn to agent instance {} (conv {} seq {})",
                    idleInstance.getId(), conversationId, assistantSequenceNo);
            // Phase 9a — archive the dispatched payload so V2 replay has
            // the same inputs the agent saw. Best-effort: never abort
            // the caller on a snapshot failure.
            snapshotWriter.write(ai.myrmec.engine.snapshot.SnapshotWriter.SnapshotRequest.builder()
                    .projectId(conversation.getProjectId())
                    .eventType("CONVERSATION_TURN_DISPATCHED")
                    .agentId(agent.getId())
                    .conversationId(conversationId)
                    .payload(payload)
                    .build());
        } else {
            log.warn("Failed to send conversation.turn.assign to agent instance {} (conv {})",
                    idleInstance.getId(), conversationId);
        }
        return sent;
    }

    private Optional<AgentInstance> findIdleInstance(UUID agentId) {
        List<AgentInstance> instances = agentInstanceRepository.findByAgentIdAndStatus(
                agentId, AgentInstance.Status.ONLINE);
        for (AgentInstance instance : instances) {
            if (connectionManager.isAgentIdle(instance.getId())) {
                return Optional.of(instance);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the most-recent {@link #HISTORY_LIMIT} messages, oldest
     * first. Always copies into an {@code ArrayList} so callers (and
     * Jackson) can iterate without surprises.
     */
    private List<ConversationTurnAssignPayload.HistoryEntry> buildSlidingWindow(
            List<ConversationMessage> all) {
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        int from = Math.max(0, all.size() - HISTORY_LIMIT);
        List<ConversationTurnAssignPayload.HistoryEntry> out = new ArrayList<>(all.size() - from);
        for (ConversationMessage m : all.subList(from, all.size())) {
            out.add(ConversationTurnAssignPayload.HistoryEntry.builder()
                    .role(m.getRole().name())
                    .content(m.getContent())
                    .sequenceNo(m.getSequenceNo())
                    .build());
        }
        return out;
    }

    private Optional<String> lastUserContent(List<ConversationMessage> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            ConversationMessage m = all.get(i);
            if (m.getRole() == ConversationMessage.Role.USER) {
                return Optional.ofNullable(m.getContent());
            }
        }
        return Optional.empty();
    }

    /**
     * Look up the model assigned to the profile and decrypt its API key.
     * Mirrors {@code TaskDispatcherService.buildTaskPayload} so behaviour
     * stays consistent between workflow tasks and conversational turns.
     */
    private TaskAssignPayload.ModelInfo resolveModel(AgentProfile profile) {
        if (profile.getDefaultModel() == null) {
            log.warn("Agent profile '{}' has no default model configured \u2014 dispatch without modelInfo",
                    profile.getName());
            return null;
        }
        try {
            Model model = modelService.findByCode(profile.getDefaultModel());
            String apiKey = null;
            if (model.isRequiresAuth() && model.getApiKeyEncrypted() != null) {
                apiKey = modelService.getApiKey(model.getCode());
            }
            String apiEndpoint = model.getApiEndpoint();
            if (apiEndpoint == null && model.getProviderConfig() != null) {
                apiEndpoint = model.getProviderConfig().getBaseUrl();
            }
            return TaskAssignPayload.ModelInfo.builder()
                    .provider(model.getProvider())
                    .modelId(model.getModelId())
                    .apiEndpoint(apiEndpoint)
                    .apiKey(apiKey)
                    .parameters(model.getDefaultParams())
                    .build();
        } catch (Exception e) {
            log.warn("Could not resolve model '{}' for profile '{}': {}",
                    profile.getDefaultModel(), profile.getName(), e.getMessage());
            return null;
        }
    }
}

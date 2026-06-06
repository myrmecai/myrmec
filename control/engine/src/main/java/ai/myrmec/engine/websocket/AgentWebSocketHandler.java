package ai.myrmec.engine.websocket;

import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
import ai.myrmec.engine.websocket.message.CloseCode;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import ai.myrmec.engine.websocket.message.payload.*;
import ai.myrmec.engine.workflow.ExecutionEventService;
import ai.myrmec.engine.workflow.LogSource;
import ai.myrmec.engine.workflow.TaskAttempt;
import ai.myrmec.engine.workflow.TaskAttemptRepository;
import ai.myrmec.engine.workflow.TaskAttemptService;
import ai.myrmec.engine.workflow.AttemptStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WebSocket handler for agent communication.
 * Handles task assignment, status updates, logs, and heartbeat.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final AgentInstanceRepository agentInstanceRepository;
    private final AgentConnectionManager connectionManager;
    private final ExecutionEventService executionEventService;
    private final TaskAttemptService taskAttemptService;
    private final TaskAttemptRepository taskAttemptRepository;
    private final ObjectMapper objectMapper;

    private static final String ATTR_AGENT_INSTANCE_ID = "agentInstanceId";
    private static final String ATTR_AGENT_NAME = "agentName";

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);
        String agentName = (String) session.getAttributes().get(ATTR_AGENT_NAME);

        if (agentInstanceId == null) {
            log.warn("Connection established without agent instance ID, closing");
            session.close(CloseCode.INVALID_TOKEN);
            return;
        }

        // Update agent instance status in database
        agentInstanceRepository.findById(agentInstanceId).ifPresent(instance -> {
            instance.setStatus(AgentInstance.Status.ONLINE);
            instance.recordHeartbeat();
            agentInstanceRepository.save(instance);
        });

        // Register with connection manager
        connectionManager.register(agentInstanceId, agentName, session);

        log.info("Agent WebSocket connected: {} ({})", agentName, agentInstanceId);

        // Check for running attempts that were assigned to this agent (reconnection scenario)
        checkRunningAttempts(agentInstanceId);
    }

    /**
     * Check for running attempts assigned to this agent and request status.
     * This handles reconnection scenarios where the agent may have results.
     */
    private void checkRunningAttempts(UUID agentInstanceId) {
        List<TaskAttempt> runningAttempts = taskAttemptRepository
                .findByAgentInstanceIdAndStatus(agentInstanceId, AttemptStatus.RUNNING);

        if (!runningAttempts.isEmpty()) {
            log.info("Agent {} reconnected with {} running attempts, requesting status",
                    agentInstanceId, runningAttempts.size());

            for (TaskAttempt attempt : runningAttempts) {
                requestTaskStatus(agentInstanceId, attempt.getTask().getId(), attempt.getId());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        // Update agent instance status to offline
        if (agentInstanceId != null) {
            agentInstanceRepository.findById(agentInstanceId).ifPresent(instance -> {
                instance.markOffline();
                agentInstanceRepository.save(instance);
            });

            // If not a graceful disconnect, mark running attempts as abandoned
            if (!status.equals(CloseStatus.NORMAL)) {
                abandonRunningAttempts(agentInstanceId, 
                        "Agent disconnected unexpectedly: " + status.getReason());
            }
        }

        // Unregister from connection manager
        connectionManager.unregister(session);

        log.info("Agent WebSocket disconnected: {} (reason: {})",
                agentInstanceId, status.getReason());
    }

    /**
     * Mark all running attempts for this agent as abandoned.
     */
    private void abandonRunningAttempts(UUID agentInstanceId, String reason) {
        List<TaskAttempt> runningAttempts = taskAttemptRepository
                .findByAgentInstanceIdAndStatus(agentInstanceId, AttemptStatus.RUNNING);

        if (!runningAttempts.isEmpty()) {
            log.warn("Abandoning {} running attempts for disconnected agent {}",
                    runningAttempts.size(), agentInstanceId);

            for (TaskAttempt attempt : runningAttempts) {
                try {
                    taskAttemptService.markAbandoned(attempt.getId(), reason);
                } catch (Exception e) {
                    log.error("Failed to mark attempt {} as abandoned: {}", 
                            attempt.getId(), e.getMessage());
                }
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        try {
            JsonNode node = objectMapper.readTree(payload);
            String type = node.path("type").asText();
            JsonNode payloadNode = node.path("payload");

            switch (type) {
                case MessageType.TASK_ACCEPT -> handleTaskAccept(session, payloadNode);
                case MessageType.TASK_REJECT -> handleTaskReject(session, payloadNode);
                case MessageType.TASK_PROGRESS -> handleTaskProgress(session, payloadNode);
                case MessageType.TASK_COMPLETE -> handleTaskComplete(session, payloadNode);
                case MessageType.TASK_FAILED -> handleTaskFailed(session, payloadNode);
                case MessageType.TASK_SKIPPED -> handleTaskSkipped(session, payloadNode);
                case MessageType.TASK_STATUS_RESPONSE -> handleTaskStatusResponse(session, payloadNode);
                case MessageType.LOG -> handleLog(session, payloadNode);
                case MessageType.TOOL_CALL -> handleToolCall(session, payloadNode);
                case MessageType.TOOL_RESULT -> handleToolResult(session, payloadNode);
                case MessageType.TOKEN_USAGE -> handleTokenUsage(session, payloadNode);
                case MessageType.TASK_METRICS -> handleTaskMetrics(session, payloadNode);
                case MessageType.PONG -> handlePong(session);
                case MessageType.DISCONNECT -> handleDisconnect(session, payloadNode);
                default -> log.warn("Unknown message type: {}", type);
            }
        } catch (JsonProcessingException e) {
            log.error("Invalid JSON message from agent: {}", e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);
        log.error("WebSocket transport error for agent {}: {}", agentInstanceId, exception.getMessage());

        // Mark agent as error state
        if (agentInstanceId != null) {
            agentInstanceRepository.findById(agentInstanceId).ifPresent(instance -> {
                instance.markError();
                agentInstanceRepository.save(instance);
            });
        }

        // Close the session
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    // ==================== Message Handlers ====================

    private void handleTaskAccept(WebSocketSession session, JsonNode payload) {
        TaskAcceptPayload accept = objectMapper.convertValue(payload, TaskAcceptPayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        log.info("Agent {} accepted task {}", agentInstanceId, accept.getTaskId());

        // TODO: Update task status in database to RUNNING
        // TODO: Notify task service that task was accepted
    }

    private void handleTaskReject(WebSocketSession session, JsonNode payload) {
        TaskRejectPayload reject = objectMapper.convertValue(payload, TaskRejectPayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        log.info("Agent {} rejected task {}: {}", agentInstanceId, reject.getTaskId(), reject.getReason());

        // Clear task assignment
        connectionManager.clearTask(agentInstanceId);

        // TODO: Return task to queue for reassignment
        // TODO: Notify task service that task was rejected
    }

    private void handleTaskProgress(WebSocketSession session, JsonNode payload) {
        TaskProgressPayload progress = objectMapper.convertValue(payload, TaskProgressPayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        log.debug("Agent {} task {} progress: {}% - {}",
                agentInstanceId, progress.getTaskId(), progress.getProgress(), progress.getMessage());

        // Store progress event
        try {
            executionEventService.recordProgress(
                    progress.getTaskId(),
                    progress.getAttemptId(),
                    progress.getProgress(),
                    progress.getMessage()
            );
        } catch (Exception e) {
            log.warn("Failed to store progress event: {}", e.getMessage());
        }
    }

    private void handleTaskComplete(WebSocketSession session, JsonNode payload) {
        TaskCompletePayload complete = objectMapper.convertValue(payload, TaskCompletePayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        // Resolve attemptId - use provided or look up current attempt for task
        UUID attemptId = complete.getAttemptId();
        if (attemptId == null) {
            attemptId = taskAttemptService.findCurrentAttemptId(complete.getTaskId()).orElse(null);
            log.debug("Resolved attemptId {} for task {} (agent didn't provide)", 
                    attemptId, complete.getTaskId());
        }

        log.info("Agent {} completed task {} (attempt {})", 
                agentInstanceId, complete.getTaskId(), attemptId);

        // Clear task assignment
        connectionManager.clearTask(agentInstanceId);

        // Complete the attempt and update task
        if (attemptId != null) {
            try {
                taskAttemptService.completeSuccess(attemptId, complete.getResult());
            } catch (Exception e) {
                log.error("Failed to complete task attempt: {}", e.getMessage(), e);
            }
        } else {
            log.warn("No attempt found for completed task {}, skipping attempt update", 
                    complete.getTaskId());
        }
    }

    private void handleTaskFailed(WebSocketSession session, JsonNode payload) {
        TaskFailedPayload failed = objectMapper.convertValue(payload, TaskFailedPayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        // Resolve attemptId - use provided or look up current attempt for task
        UUID attemptId = failed.getAttemptId();
        if (attemptId == null) {
            attemptId = taskAttemptService.findCurrentAttemptId(failed.getTaskId()).orElse(null);
        }

        log.warn("Agent {} failed task {} (attempt {}) [{}]: {}",
                agentInstanceId, failed.getTaskId(), attemptId, 
                failed.getErrorCode(), failed.getError());

        // Clear task assignment
        connectionManager.clearTask(agentInstanceId);

        // Mark the attempt as failed
        if (attemptId != null) {
            try {
                taskAttemptService.completeFailed(attemptId, failed.getError(), failed.getErrorCode());
            } catch (Exception e) {
                log.error("Failed to record task failure: {}", e.getMessage(), e);
            }
        } else {
            log.warn("No attempt found for failed task {}, skipping attempt update", 
                    failed.getTaskId());
        }
    }

    private void handleTaskSkipped(WebSocketSession session, JsonNode payload) {
        TaskSkippedPayload skipped = objectMapper.convertValue(payload, TaskSkippedPayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        log.info("Agent {} skipped task {} (attempt {}): {}",
                agentInstanceId, skipped.getTaskId(), skipped.getAttemptId(), skipped.getReason());

        // Clear task assignment
        connectionManager.clearTask(agentInstanceId);

        // Mark the attempt as skipped (does not count toward retry limit)
        try {
            taskAttemptService.markSkipped(skipped.getAttemptId(), skipped.getReason());
        } catch (Exception e) {
            log.error("Failed to record task skip: {}", e.getMessage(), e);
        }
    }

    private void handleTaskStatusResponse(WebSocketSession session, JsonNode payload) {
        TaskStatusResponsePayload response = objectMapper.convertValue(payload, TaskStatusResponsePayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        log.info("Agent {} task status response for {} (attempt {}): {}",
                agentInstanceId, response.getTaskId(), response.getAttemptId(), response.getStatus());

        switch (response.getStatus()) {
            case "running" -> {
                // Task still running, update heartbeat
                log.debug("Task {} still running on agent {}", response.getTaskId(), agentInstanceId);
            }
            case "completed" -> {
                // Task completed while disconnected, process completion
                log.info("Task {} completed while agent {} was disconnected", 
                        response.getTaskId(), agentInstanceId);
                connectionManager.clearTask(agentInstanceId);
                try {
                    taskAttemptService.completeSuccess(response.getAttemptId(), response.getResult());
                } catch (Exception e) {
                    log.error("Failed to process delayed task completion: {}", e.getMessage(), e);
                }
            }
            case "failed" -> {
                // Task failed while disconnected
                log.warn("Task {} failed while agent {} was disconnected: {}",
                        response.getTaskId(), agentInstanceId, response.getError());
                connectionManager.clearTask(agentInstanceId);
                try {
                    taskAttemptService.completeFailed(
                            response.getAttemptId(), response.getError(), response.getErrorCode());
                } catch (Exception e) {
                    log.error("Failed to process delayed task failure: {}", e.getMessage(), e);
                }
            }
            case "unknown" -> {
                // Agent doesn't know about this task - mark as abandoned
                log.warn("Agent {} doesn't recognize task {}, marking as abandoned",
                        agentInstanceId, response.getTaskId());
                connectionManager.clearTask(agentInstanceId);
                try {
                    taskAttemptService.markAbandoned(response.getAttemptId(), 
                            "Agent lost task context after reconnect");
                } catch (Exception e) {
                    log.error("Failed to mark task as abandoned: {}", e.getMessage(), e);
                }
            }
            default -> log.warn("Unknown task status: {}", response.getStatus());
        }
    }

    private void handleLog(WebSocketSession session, JsonNode payload) {
        LogPayload logEntry = objectMapper.convertValue(payload, LogPayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        // Log to engine logs
        switch (logEntry.getLevel().toUpperCase()) {
            case "DEBUG" -> log.debug("[Agent {}] {}", agentInstanceId, logEntry.getMessage());
            case "INFO" -> log.info("[Agent {}] {}", agentInstanceId, logEntry.getMessage());
            case "WARN" -> log.warn("[Agent {}] {}", agentInstanceId, logEntry.getMessage());
            case "ERROR" -> log.error("[Agent {}] {}", agentInstanceId, logEntry.getMessage());
            default -> log.info("[Agent {}] {}", agentInstanceId, logEntry.getMessage());
        }

        // Store log event
        try {
            LogSource source = parseLogSource(logEntry.getSource());
            executionEventService.recordLog(
                    logEntry.getTaskId(),
                    logEntry.getAttemptId(),
                    logEntry.getLevel(),
                    logEntry.getMessage(),
                    logEntry.getData(),
                    source
            );
        } catch (Exception e) {
            log.warn("Failed to store log event: {}", e.getMessage());
        }
    }

    /**
     * Parse source string from agent to LogSource enum.
     * Defaults to TASK if null or unrecognized.
     */
    private LogSource parseLogSource(String source) {
        if (source == null) {
            return LogSource.TASK;
        }
        try {
            return LogSource.valueOf(source.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown log source '{}', defaulting to TASK", source);
            return LogSource.TASK;
        }
    }

    private void handleToolCall(WebSocketSession session, JsonNode payload) {
        ToolCallPayload toolCall = objectMapper.convertValue(payload, ToolCallPayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        log.debug("Agent {} task {} calling tool {}: {}",
                agentInstanceId, toolCall.getTaskId(), toolCall.getToolName(), toolCall.getCallId());

        // Store tool call event
        try {
            executionEventService.recordToolCall(
                    toolCall.getTaskId(),
                    toolCall.getAttemptId(),
                    toolCall.getCallId(),
                    toolCall.getToolName(),
                    toolCall.getInput()
            );
        } catch (Exception e) {
            log.warn("Failed to store tool call event: {}", e.getMessage());
        }
    }

    private void handleToolResult(WebSocketSession session, JsonNode payload) {
        ToolResultPayload toolResult = objectMapper.convertValue(payload, ToolResultPayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        log.debug("Agent {} task {} tool {} result ({}ms): {}",
                agentInstanceId, toolResult.getTaskId(), toolResult.getCallId(),
                toolResult.getDurationMs(), toolResult.getError() != null ? "failed" : "success");

        // Store tool result event
        try {
            executionEventService.recordToolResult(
                    toolResult.getTaskId(),
                    toolResult.getAttemptId(),
                    toolResult.getCallId(),
                    null, // Tool name not in result payload, will be correlated by callId
                    toolResult.getDurationMs(),
                    toolResult.getError() != null,
                    toolResult.getError()
            );
        } catch (Exception e) {
            log.warn("Failed to store tool result event: {}", e.getMessage());
        }
    }

    private void handleTokenUsage(WebSocketSession session, JsonNode payload) {
        TokenUsagePayload usage = objectMapper.convertValue(payload, TokenUsagePayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        log.debug("Agent {} task {} token usage [{}]: prompt={} completion={} total={}",
                agentInstanceId, usage.getTaskId(), usage.getModel(),
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());

        UUID attemptId = usage.getAttemptId();
        if (attemptId == null) {
            attemptId = taskAttemptService.findCurrentAttemptId(usage.getTaskId()).orElse(null);
        }

        try {
            executionEventService.recordTokenUsage(
                    usage.getTaskId(),
                    attemptId,
                    usage.getModel(),
                    usage.getCallId(),
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    usage.getTotalTokens(),
                    usage.getDurationMs()
            );
        } catch (Exception e) {
            log.warn("Failed to store token usage event: {}", e.getMessage());
        }
    }

    private void handleTaskMetrics(WebSocketSession session, JsonNode payload) {
        TaskMetricsPayload metrics = objectMapper.convertValue(payload, TaskMetricsPayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        log.debug("Agent {} task {} metrics summary: totalDurationMs={} modelDurationMs={} toolDurationMs={}",
                agentInstanceId, metrics.getTaskId(),
                metrics.getTotalDurationMs(), metrics.getModelDurationMs(), metrics.getToolDurationMs());

        UUID attemptId = metrics.getAttemptId();
        if (attemptId == null) {
            attemptId = taskAttemptService.findCurrentAttemptId(metrics.getTaskId()).orElse(null);
        }

        Map<String, Object> data = objectMapper.convertValue(metrics, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        try {
            executionEventService.recordTaskMetrics(metrics.getTaskId(), attemptId, data);
        } catch (Exception e) {
            log.warn("Failed to store task metrics event: {}", e.getMessage());
        }
    }

    private void handlePong(WebSocketSession session) {
        connectionManager.recordPong(session);

        // Update heartbeat in database
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);
        if (agentInstanceId != null) {
            agentInstanceRepository.findById(agentInstanceId).ifPresent(instance -> {
                instance.recordHeartbeat();
                agentInstanceRepository.save(instance);
            });
        }
    }

    private void handleDisconnect(WebSocketSession session, JsonNode payload) throws IOException {
        DisconnectPayload disconnect = objectMapper.convertValue(payload, DisconnectPayload.class);
        UUID agentInstanceId = (UUID) session.getAttributes().get(ATTR_AGENT_INSTANCE_ID);

        log.info("Agent {} requesting graceful disconnect: {}", agentInstanceId, disconnect.getReason());

        // Close session normally
        session.close(CloseStatus.NORMAL);
    }

    // ==================== Outbound Messages ====================

    /**
     * Send a task assignment to an agent.
     */
    public boolean assignTask(UUID agentInstanceId, TaskAssignPayload task) {
        WebSocketMessage<TaskAssignPayload> message = WebSocketMessage.of(MessageType.TASK_ASSIGN, task);

        boolean sent = connectionManager.sendMessage(agentInstanceId, message);
        if (sent) {
            connectionManager.assignTask(agentInstanceId, task.getTaskId());
        }
        return sent;
    }

    /**
     * Send a task cancellation to an agent.
     */
    public boolean cancelTask(UUID agentInstanceId, UUID taskId, String reason) {
        TaskCancelPayload payload = TaskCancelPayload.builder()
                .taskId(taskId)
                .reason(reason)
                .build();

        WebSocketMessage<TaskCancelPayload> message = WebSocketMessage.of(MessageType.TASK_CANCEL, payload);
        return connectionManager.sendMessage(agentInstanceId, message);
    }

    /**
     * Request task status from an agent (e.g., after reconnect).
     */
    public boolean requestTaskStatus(UUID agentInstanceId, UUID taskId, UUID attemptId) {
        TaskStatusRequestPayload payload = TaskStatusRequestPayload.builder()
                .taskId(taskId)
                .attemptId(attemptId)
                .build();

        WebSocketMessage<TaskStatusRequestPayload> message = 
                WebSocketMessage.of(MessageType.TASK_STATUS_REQUEST, payload);
        return connectionManager.sendMessage(agentInstanceId, message);
    }
}

package ai.myrmec.engine.websocket.message.payload;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for task.assign message (Engine → Agent).
 */
@Data
@Builder
public class TaskAssignPayload {
    
    /** Unique task identifier */
    private UUID taskId;
    
    /** Attempt identifier for this execution */
    private UUID attemptId;
    
    /** Attempt number (1-based) */
    private int attemptNumber;
    
    /** Workflow this task belongs to */
    private UUID workflowId;
    
    /** Step index within workflow (0-based) */
    private int stepIndex;
    
    /** Step name for display */
    private String stepName;
    
    /** 
     * System prompt from agent profile.
     * Defines agent expertise, personality, and general behavior.
     */
    private String systemPrompt;
    
    /**
     * Task-specific prompt from workflow step definition.
     * Contains instructions for this specific task.
     */
    private String stepPrompt;
    
    /** Input data for the task */
    private Map<String, Object> input;
    
    /** Tools available for this task */
    private List<ToolDefinition> tools;
    
    /** Task timeout in seconds */
    private int timeoutSeconds;
    
    /** Model information for LLM tasks */
    private ModelInfo model;
    
    /** 
     * Execution context with knowledge documents and RAG config.
     * Contains org/project instructions, standards, and requirements.
     */
    private TaskContext context;
    
    @Data
    @Builder
    public static class ToolDefinition {
        private String name;
        private String description;
        private Map<String, Object> parameters;
    }
    
    @Data
    @Builder
    public static class ModelInfo {
        /** Provider code (openai, anthropic, ollama, etc.) */
        private String provider;
        
        /** Model identifier (gpt-4-turbo, claude-3-opus, etc.) */
        private String modelId;
        
        /** API endpoint URL (for cloud providers or custom endpoints) */
        private String apiEndpoint;
        
        /** Decrypted API key for authentication */
        private String apiKey;
        
        /** Optional inference parameters (temperature, maxTokens, etc.) */
        private Map<String, Object> parameters;
    }
}

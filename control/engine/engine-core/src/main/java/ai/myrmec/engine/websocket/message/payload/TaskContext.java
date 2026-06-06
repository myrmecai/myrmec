package ai.myrmec.engine.websocket.message.payload;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Task execution context containing resolved knowledge documents
 * and optional RAG configuration for external knowledge retrieval.
 * 
 * This context is injected into TaskAssignPayload and sent to agents
 * along with task input to provide organizational standards, project
 * conventions, and task-specific instructions.
 */
@Data
@Builder
public class TaskContext {
    
    /**
     * Compiled knowledge documents relevant to this task.
     * Ordered by priority (highest first), filtered by appliesTo patterns.
     */
    private List<KnowledgeEntry> knowledge;
    
    /**
     * Optional RAG configuration for external knowledge retrieval.
     * If present, the agent can query an external RAG system for
     * additional context beyond the embedded knowledge.
     */
    private RagConfig rag;
    
    /**
     * Total character count of embedded knowledge (for monitoring).
     */
    private int knowledgeCharCount;
    
    /**
     * Workspace configuration for file-based operations.
     * Contains repo URL and branch for agent to clone/checkout.
     */
    private WorkspaceConfig workspace;
    
    /**
     * A single knowledge document entry.
     */
    @Data
    @Builder
    public static class KnowledgeEntry {
        /** Document category (STANDARD, INSTRUCTION, REQUIREMENT, ARCHITECTURE) */
        private String category;
        
        /** Human-readable document name */
        private String name;
        
        /** Document content (markdown or plain text) */
        private String content;
        
        /** Priority used for sorting (higher = more important) */
        private int priority;
    }
    
    /**
     * Configuration for external RAG system access.
     */
    @Data
    @Builder
    public static class RagConfig {
        /** RAG API endpoint URL */
        private String endpoint;
        
        /** Secret name for API key (agent retrieves from vault) */
        private String apiKeySecret;
        
        /** Collection/index name to query */
        private String collection;
        
        /** Maximum number of results to retrieve */
        private int topK;
    }
    
    /**
     * Workspace configuration for file-based task execution.
     * Agent clones the repo to a local temp directory.
     */
    @Data
    @Builder
    public static class WorkspaceConfig {
        /** Git repository URL (HTTPS or SSH) */
        private String repoUrl;
        
        /** Branch to checkout (default: main) */
        private String branch;
        
        /** Optional subdirectory within repo to use as workspace root */
        private String subPath;
        
        /** Git authentication token for private repos (PAT or deploy key) */
        private String repoToken;
    }
}

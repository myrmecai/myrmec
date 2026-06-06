package ai.myrmec.engine.knowledge;

/**
 * Scope of a knowledge document.
 */
public enum KnowledgeScope {
    /**
     * Organization-wide knowledge (applies to all projects).
     * project_id must be null.
     */
    ORGANIZATION,
    
    /**
     * Project-specific knowledge.
     * project_id must be set.
     */
    PROJECT
}

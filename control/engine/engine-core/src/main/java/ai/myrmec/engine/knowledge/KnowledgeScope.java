package ai.myrmec.engine.knowledge;

/**
 * Scope of a knowledge document.
 */
public enum KnowledgeScope {
    /**
     * Organization-wide knowledge (applies to all projects).
     * team_id must be null.
     */
    ORGANIZATION,
    
    /**
     * Team-specific knowledge.
     * team_id must be set.
     */
    Team
}

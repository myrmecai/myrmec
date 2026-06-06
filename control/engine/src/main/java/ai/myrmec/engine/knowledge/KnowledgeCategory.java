package ai.myrmec.engine.knowledge;

/**
 * Category of a knowledge document.
 */
public enum KnowledgeCategory {
    /**
     * Coding conventions, style guides, security policies.
     * Example: "Always use parameterized queries", "Follow PEP 8".
     */
    STANDARD,
    
    /**
     * How-to guides, patterns to follow.
     * Example: .github/instructions files, implementation guides.
     */
    INSTRUCTION,
    
    /**
     * Functional and non-functional requirements.
     * Example: Feature specs, acceptance criteria, performance targets.
     */
    REQUIREMENT,
    
    /**
     * System design documentation.
     * Example: Architecture diagrams, ERDs, decision records.
     */
    ARCHITECTURE
}

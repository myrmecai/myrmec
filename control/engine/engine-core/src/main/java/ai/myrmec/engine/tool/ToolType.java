package ai.myrmec.engine.tool;

/**
 * Types of tools available in the system.
 */
public enum ToolType {
    /** System-level tools (fetch, etc.) */
    SYSTEM,
    /** File management tools (read, write, search, etc.) */
    FILE,
    /** External service integrations (GitHub, Slack, etc.) */
    INTEGRATION,
    /** Database tools (PostgreSQL, MySQL, etc.) */
    DATABASE,
    /** Git version control tools */
    GIT,
    /** Custom user-defined tools */
    CUSTOM
}

package ai.myrmec.engine.tool;

/**
 * Risk classification for a registered tool. Drives HITL gating in
 * Phase 7 onwards.
 *
 * <ul>
 *   <li>{@link #SAFE} — read-only or fully reversible (e.g. {@code github.list_prs},
 *       {@code postgres.select}). Never triggers approval.</li>
 *   <li>{@link #WRITE} — creates or mutates state but cleanly reversible
 *       within seconds (e.g. open a PR, create a JIRA). Triggers
 *       approval only when project policy is set to maximum strictness
 *       (future).</li>
 *   <li>{@link #DESTRUCTIVE} — deletes / overwrites / merges. Triggers
 *       approval whenever {@code projects.auto_hitl_on_destructive=true}.</li>
 *   <li>{@link #IRREVERSIBLE} — production deploys, schema migrations,
 *       payments. ALWAYS triggers approval when the project policy is
 *       on, regardless of other settings.</li>
 * </ul>
 *
 * <p>Default for new and seeded tools is {@link #SAFE} — administrators
 * must consciously classify any tool that mutates state.</p>
 */
public enum RiskClass {
    SAFE,
    WRITE,
    DESTRUCTIVE,
    IRREVERSIBLE
}

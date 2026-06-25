package ai.myrmec.engine.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * The kinds of automated business work a project can host (#76 vocabulary
 * lock). The set is intentionally small today — {@code WORKFLOW} and
 * {@code CONVERSATIONAL} are the two shipped types — but the enum is the single
 * source of truth that the per-project {@code allowed_service_types} gate (#77)
 * and the platform Service-Type registry (#78) both build on.
 *
 * <p>Each constant carries presentation metadata (display name, description,
 * icon hint) so the read-only Platform → Service Types page can render without
 * a second source of truth. Future types ({@code VOICE_SESSION},
 * {@code BROWSER_SESSION}, {@code FORM}, …) slot in here.</p>
 */
public enum ServiceType {

    WORKFLOW(
            "Workflow",
            "Deterministic, multi-step automation: an Execution runs a sequence of Steps to completion.",
            "workflow"),

    CONVERSATIONAL(
            "Conversational",
            "Interactive assistants: an end user opens a Conversation Session and exchanges Messages with an agent.",
            "message-square");

    private final String displayName;
    private final String description;
    private final String icon;

    ServiceType(String displayName, String description, String icon) {
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public String icon() {
        return icon;
    }

    /**
     * Case-insensitive lookup that never throws — used when validating an
     * inbound {@code allowed_service_types} list so an unknown string yields a
     * clean validation error rather than an {@link IllegalArgumentException}.
     */
    public static Optional<ServiceType> fromString(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}

package ai.myrmec.engine._system.exception;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 409 Conflict for Assistant version Draft contention (#92, assistant-entity.md
 * §5.4 / §5.5):
 *
 * <ul>
 *   <li>{@code SINGLE_DRAFT} — a Draft already exists when one tries to create
 *       a new version. Payload lets the UI offer view / take-over.</li>
 *   <li>{@code STALE_DRAFT} — the Draft was forked from a version that has since
 *       been superseded by a newer Published version.</li>
 * </ul>
 */
@Getter
public class DraftConflictException extends RuntimeException {

    public enum Kind { SINGLE_DRAFT, STALE_DRAFT }

    private final Kind kind;
    private final UUID draftId;
    private final UUID draftOwnerId;
    private final Instant draftStartedAt;
    private final String currentVersionNumber;

    private DraftConflictException(String message, Kind kind, UUID draftId, UUID draftOwnerId,
                                   Instant draftStartedAt, String currentVersionNumber) {
        super(message);
        this.kind = kind;
        this.draftId = draftId;
        this.draftOwnerId = draftOwnerId;
        this.draftStartedAt = draftStartedAt;
        this.currentVersionNumber = currentVersionNumber;
    }

    public static DraftConflictException singleDraft(UUID draftId, UUID draftOwnerId, Instant draftStartedAt) {
        return new DraftConflictException(
                "A draft is already open for this assistant.",
                Kind.SINGLE_DRAFT, draftId, draftOwnerId, draftStartedAt, null);
    }

    public static DraftConflictException staleDraft(String currentVersionNumber) {
        return new DraftConflictException(
                "Version " + currentVersionNumber + " was published while you were editing. "
                        + "Please discard your draft and start a new one.",
                Kind.STALE_DRAFT, null, null, null, currentVersionNumber);
    }
}

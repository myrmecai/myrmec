package ai.myrmec.engine.attachment;

/**
 * Scan lifecycle of a {@link ConversationMessageAttachment} (#104). Because
 * the scan gate runs synchronously during upload — <em>before</em> any bytes
 * are stored — a persisted row is only ever {@link #CLEAN} (stored, usable),
 * {@link #INFECTED}, or {@link #ERROR} (both quarantined: metadata retained
 * for transparency + audit, no blob stored, never exposed to an agent).
 */
public enum AttachmentScanStatus {

    /** Passed the scan; bytes are stored and the attachment is usable. */
    CLEAN,

    /** A threat was detected; quarantined (no blob stored). */
    INFECTED,

    /** The scanner could not complete; quarantined fail-closed. */
    ERROR
}

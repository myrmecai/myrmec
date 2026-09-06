// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The four deterministic UUIDv5 namespaces and the canonical digest
 * derivation inputs (design §16.1). These are fixed design constants;
 * the TypeScript SDK mirrors them verbatim in
 * {@code agents/src/orchestration/constants.ts} — the two languages must
 * never drift, so any change here is a wire-breaking change requiring the
 * same change there.
 *
 * <p>Each namespace hashes a different name shape: result digest, dispatch
 * sequence, run/task/episode, release/generation. The derivation inputs —
 * field selection, order, and the {@code ":"} separator — are part of the
 * contract at each UUIDv5 site; changing a field order is as breaking as
 * changing the namespace.</p>
 */
public final class OrchestrationIds {

    /** UUIDv5 namespace for terminal result IDs (result-digest shape). */
    public static final UUID ORCHESTRATION_RESULT_NS =
            UUID.fromString("ad694481-13ed-40ec-9816-645cfe953e2f");

    /** UUIDv5 namespace for Agent orchestration event IDs (dispatch-sequence shape). */
    public static final UUID ORCHESTRATION_EVENT_NS =
            UUID.fromString("97ebe21e-037e-4b3a-adb0-8b57c166e4d5");

    /** UUIDv5 namespace for engine-owned scheduling events (run/task/episode shape). */
    public static final UUID ORCHESTRATION_SCHEDULING_NS =
            UUID.fromString("a5ada7e8-3b1a-4d9c-a970-ec22f1f74bd2");

    /** UUIDv5 namespace for workspace release acknowledgements (release/generation shape). */
    public static final UUID WORKSPACE_ACK_NS =
            UUID.fromString("3c8ea127-18cb-465f-8edb-025ec79ef52a");

    private OrchestrationIds() {
    }

    /**
     * UUIDv5 (SHA-1, RFC 4122) name-based derivation over the given
     * namespace and name — the deterministic ID basis for results, events,
     * scheduling records, and release acknowledgements.
     */
    public static UUID uuidV5(UUID namespace, String name) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
        sha1.reset();
        // namespace UUID bytes in big-endian order, then the UTF-8 name
        long msb = namespace.getMostSignificantBits();
        long lsb = namespace.getLeastSignificantBits();
        byte[] nsBytes = new byte[16];
        for (int i = 15; i >= 0; i--) {
            nsBytes[i] = (byte) (lsb & 0xFF);
            lsb >>>= 8;
        }
        for (int i = 7; i >= 0; i--) {
            nsBytes[i] = (byte) (msb & 0xFF);
            msb >>>= 8;
        }
        sha1.update(nsBytes);
        sha1.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] digest = sha1.digest();

        // RFC 4122 §4.3: version 5, variant RFC 4122
        digest[6] = (byte) ((digest[6] & 0x0F) | 0x50);
        digest[8] = (byte) ((digest[8] & 0x3F) | 0x80);

        long msbOut = 0;
        for (int i = 0; i < 8; i++) {
            msbOut = (msbOut << 8) | (digest[i] & 0xFF);
        }
        long lsbOut = 0;
        for (int i = 8; i < 16; i++) {
            lsbOut = (lsbOut << 8) | (digest[i] & 0xFF);
        }
        return new UUID(msbOut, lsbOut);
    }

    /**
     * Scheduling-event ID: deterministic over run/task/episode/occurrence
     * (the run/task/episode name shape, §16.1).
     */
    public static UUID schedulingEventId(UUID runId, UUID taskId, int episode, int occurrence) {
        String name = runId + ":" + taskId + ":" + episode + ":" + occurrence;
        return uuidV5(ORCHESTRATION_SCHEDULING_NS, name);
    }

    /**
     * Release acknowledgement ID: deterministic over
     * {@code releaseId + ":" + generation + ":" + status} (§16.5).
     */
    public static UUID acknowledgementId(UUID releaseId, int generation, String status) {
        return uuidV5(WORKSPACE_ACK_NS, releaseId + ":" + generation + ":" + status);
    }

    /**
     * Workspace lifecycle event ID: deterministic over
     * {@code releaseId + ":" + acknowledgementId} (§16.5).
     */
    public static UUID workspaceLifecycleEventId(UUID releaseId, UUID acknowledgementId) {
        return uuidV5(ORCHESTRATION_SCHEDULING_NS, releaseId + ":" + acknowledgementId);
    }

    /** SHA-256 hex digest of the UTF-8 bytes; canonical digest basis. */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic serialization of {@link AuditEvent} fields for hash-chain
 * computation.
 *
 * <p>The canonical form is a fixed-field-order JSON-like byte sequence.
 * Verification must reproduce it <strong>byte-for-byte</strong>. The rules:
 * <ul>
 *   <li>Fixed field order (see {@link #FIELD_ORDER}).</li>
 *   <li>JSON keys sorted <em>recursively</em> (not just top-level).</li>
 *   <li>Numbers normalized: integers as bare digits, doubles as
 *       {@code toString()} (Jackson default).</li>
 *   <li>{@code null} fields serialized as {@code "null"} (present, not
 *       absent — so adding a field later doesn't silently change the hash
 *       of old rows).</li>
 *   <li>Timestamps as ISO-8601 strings (Jackson default for
 *       {@link Instant}).</li>
 *   <li>UUIDs as canonical lowercase string form.</li>
 * </ul>
 *
 * <p>The output is UTF-8 bytes. The HMAC is computed over these bytes.
 */
@Component
public final class AuditChainCanonicalizer {

    /**
     * Fixed field order. The hash input is
     * {@code canonical(prevEventHash) ‖ canonical(fields…)}.
     * <strong>Never reorder or insert between existing fields</strong> —
     * that would break all existing chains. Append new fields at the end.
     */
    public static final String[] FIELD_ORDER = {
            "eventType",
            "entityType",
            "entityId",
            "versionId",
            "scopeType",
            "projectId",
            "actorId",
            "actorDisplayName",
            "timestamp",
            "reasonCode",
            "beforeSnapshot",
            "afterSnapshot",
            "metadata",
    };

    /**
     * The genesis constant — used as {@code prevEventHash} for the first
     * row in a segment. A fixed, recognizable value so the chain has a
     * deterministic starting point.
     */
    public static final String GENESIS_PREV_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private final ObjectMapper mapper;

    public AuditChainCanonicalizer() {
        this.mapper = new ObjectMapper();
        this.mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    }

    /**
     * Canonicalize the hashable fields of an {@link AuditEvent} into
     * deterministic UTF-8 bytes.
     *
     * @param event the audit event (must have all fields populated)
     * @return canonical byte representation
     */
    public byte[] canonicalize(AuditEvent event) {
        StringBuilder sb = new StringBuilder(512);
        for (String field : FIELD_ORDER) {
            appendField(sb, field, event);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Canonicalize a {@code prevEventHash} string for the hash input.
     * Used to prepend the previous hash to the field content.
     *
     * @param prevHash the previous event's hash, or {@link #GENESIS_PREV_HASH}
     * @return UTF-8 bytes of the hash string
     */
    public byte[] canonicalizePrevHash(String prevHash) {
        String h = (prevHash == null) ? GENESIS_PREV_HASH : prevHash;
        return h.getBytes(StandardCharsets.UTF_8);
    }

    private void appendField(StringBuilder sb, String field, AuditEvent event) {
        sb.append(field).append('=');
        switch (field) {
            case "eventType" -> appendString(sb, event.getEventType());
            case "entityType" -> appendString(sb, event.getEntityType());
            case "entityId" -> appendUuid(sb, event.getEntityId());
            case "versionId" -> appendUuid(sb, event.getVersionId());
            case "scopeType" -> appendString(sb, event.getScopeType());
            case "projectId" -> appendUuid(sb, event.getProjectId());
            case "actorId" -> appendUuid(sb, event.getActorId());
            case "actorDisplayName" -> appendString(sb, event.getActorDisplayName());
            case "timestamp" -> appendInstant(sb, event.getTimestamp());
            case "reasonCode" -> appendString(sb, event.getReasonCode());
            case "beforeSnapshot" -> appendMap(sb, event.getBeforeSnapshot());
            case "afterSnapshot" -> appendMap(sb, event.getAfterSnapshot());
            case "metadata" -> appendMap(sb, event.getMetadata());
            default -> throw new IllegalStateException("Unknown field: " + field);
        }
        sb.append('\n');
    }

    private void appendString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value);
        }
    }

    private void appendUuid(StringBuilder sb, UUID value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value.toString());
        }
    }

    private void appendInstant(StringBuilder sb, Instant value) {
        if (value == null) {
            sb.append("null");
        } else {
            // Truncate to milliseconds to match database precision
            // (PostgreSQL/H2 store timestamp with millisecond precision).
            // Without this, the write-time Instant has nanoseconds but
            // the read-back Instant has only milliseconds, causing hash mismatch.
            sb.append(value.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
        }
    }

    private void appendMap(StringBuilder sb, Map<String, Object> map) {
        if (map == null) {
            sb.append("null");
            return;
        }
        try {
            // ORDER_MAP_ENTRIES_BY_KEYS ensures recursive key sorting.
            // withExactBigDecimals ensures number stability.
            sb.append(mapper.writeValueAsString(map));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalize map", e);
        }
    }
}
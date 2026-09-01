// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AuditChainCanonicalizer}.
 *
 * <p>Verifies deterministic serialization — same input always produces the
 * same bytes. This is the make-or-break detail for hash-chain integrity.
 */
@Tag("INV-6")
@DisplayName("AuditChainCanonicalizer — deterministic serialization")
class AuditChainCanonicalizerTest {

    private final AuditChainCanonicalizer canonicalizer = new AuditChainCanonicalizer();

    @Test
    @DisplayName("same event produces same canonical bytes")
    void deterministicOutput() {
        AuditEvent event = sampleEvent();
        byte[] first = canonicalizer.canonicalize(event);
        byte[] second = canonicalizer.canonicalize(event);
        assertArrayEquals(first, second, "Same input must produce identical bytes");
    }

    @Test
    @DisplayName("map key order does not affect output (recursive sort)")
    void mapKeyOrderIrrelevant() {
        AuditEvent a = makeEventWithMetadata(Map.of("z", "1", "a", "2"));
        AuditEvent b = makeEventWithMetadata(Map.of("a", "2", "z", "1"));
        assertArrayEquals(canonicalizer.canonicalize(a), canonicalizer.canonicalize(b),
                "Map key insertion order must not affect canonical bytes");
    }

    @Test
    @DisplayName("null fields are serialized as 'null', not absent")
    void nullFieldsPresent() {
        AuditEvent event = new AuditEvent();
        event.setEventType("CREATED");
        event.setEntityType("project");
        event.setScopeType("ORGANIZATION");
        event.setActorDisplayName("admin");
        event.setTimestamp(Instant.parse("2026-08-20T10:00:00Z"));
        // entityId, versionId, projectId, actorId, reasonCode, snapshots all null
        byte[] result = canonicalizer.canonicalize(event);
        String str = new String(result, StandardCharsets.UTF_8);
        assertTrue(str.contains("entityId=null"), "null UUID field must be present as 'null'");
        assertTrue(str.contains("reasonCode=null"), "null String field must be present as 'null'");
        assertTrue(str.contains("beforeSnapshot=null"), "null Map field must be present as 'null'");
    }

    @Test
    @DisplayName("changing any field changes the canonical bytes")
    void anyFieldChangeBreaksBytes() {
        AuditEvent base = sampleEvent();
        byte[] baseBytes = canonicalizer.canonicalize(base);

        // Change eventType
        AuditEvent modified = copyEvent(base);
        modified.setEventType("UPDATED");
        byte[] modifiedBytes = canonicalizer.canonicalize(modified);
        assertNotEquals(toHexString(baseBytes), toHexString(modifiedBytes),
                "Changing eventType must change canonical bytes");

        // Change timestamp
        modified = copyEvent(base);
        modified.setTimestamp(Instant.parse("2026-08-20T10:00:01Z"));
        assertNotEquals(toHexString(baseBytes), toHexString(canonicalizer.canonicalize(modified)),
                "Changing timestamp must change canonical bytes");

        // Change a map field
        modified = copyEvent(base);
        modified.setMetadata(Map.of("key", "different"));
        assertNotEquals(toHexString(baseBytes), toHexString(canonicalizer.canonicalize(modified)),
                "Changing metadata must change canonical bytes");
    }

    @Test
    @DisplayName("field order is fixed (eventType first, metadata last)")
    void fixedFieldOrder() {
        AuditEvent event = sampleEvent();
        byte[] result = canonicalizer.canonicalize(event);
        String str = new String(result, StandardCharsets.UTF_8);
        int eventTypeIdx = str.indexOf("eventType=");
        int metadataIdx = str.indexOf("metadata=");
        assertTrue(eventTypeIdx < metadataIdx, "eventType must come before metadata");
        assertTrue(eventTypeIdx == 0, "eventType must be the first field");
    }

    @Test
    @DisplayName("genesis prev-hash constant is 64 zeros")
    void genesisPrevHash() {
        byte[] genesis = canonicalizer.canonicalizePrevHash(null);
        assertArrayEquals(AuditChainCanonicalizer.GENESIS_PREV_HASH.getBytes(StandardCharsets.UTF_8), genesis);
    }

    @Test
    @DisplayName("nested map keys are sorted recursively")
    void nestedKeySorting() {
        AuditEvent a = makeEventWithMetadata(Map.of("outer", Map.of("z", 1, "a", 2)));
        AuditEvent b = makeEventWithMetadata(Map.of("outer", Map.of("a", 2, "z", 1)));
        assertArrayEquals(canonicalizer.canonicalize(a), canonicalizer.canonicalize(b),
                "Nested map key order must not affect canonical bytes");
    }

    // --- helpers ---

    private AuditEvent sampleEvent() {
        AuditEvent event = new AuditEvent();
        event.setEventType("CREATED");
        event.setEntityType("project");
        event.setEntityId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        event.setScopeType("PROJECT");
        event.setProjectId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        event.setActorId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        event.setActorDisplayName("admin");
        event.setTimestamp(Instant.parse("2026-08-20T10:00:00Z"));
        event.setReasonCode("CREATED");
        event.setBeforeSnapshot(Map.of("status", "DRAFT"));
        event.setAfterSnapshot(Map.of("status", "PUBLISHED"));
        event.setMetadata(Map.of("source", "test"));
        return event;
    }

    private AuditEvent makeEventWithMetadata(Map<String, Object> metadata) {
        AuditEvent event = new AuditEvent();
        event.setEventType("CREATED");
        event.setEntityType("project");
        event.setScopeType("ORGANIZATION");
        event.setActorDisplayName("admin");
        event.setTimestamp(Instant.parse("2026-08-20T10:00:00Z"));
        event.setMetadata(metadata);
        return event;
    }

    private AuditEvent copyEvent(AuditEvent src) {
        AuditEvent copy = new AuditEvent();
        copy.setEventType(src.getEventType());
        copy.setEntityType(src.getEntityType());
        copy.setEntityId(src.getEntityId());
        copy.setVersionId(src.getVersionId());
        copy.setScopeType(src.getScopeType());
        copy.setProjectId(src.getProjectId());
        copy.setActorId(src.getActorId());
        copy.setActorDisplayName(src.getActorDisplayName());
        copy.setTimestamp(src.getTimestamp());
        copy.setReasonCode(src.getReasonCode());
        copy.setBeforeSnapshot(src.getBeforeSnapshot());
        copy.setAfterSnapshot(src.getAfterSnapshot());
        copy.setMetadata(src.getMetadata());
        return copy;
    }

    private String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-language UUIDv5 vector tests (design §16.1). The expected values
 * are shared with the TypeScript SDK's
 * {@code agents/src/orchestration/constants.test.ts} — both sides must
 * derive identical IDs from identical inputs or correlation fails closed.
 */
@DisplayName("F10: OrchestrationIds contract (§16.1)")
class OrchestrationIdsContractTest {

    @Test
    @DisplayName("namespace constants are the fixed design values")
    void namespaceConstantsAreFixed() {
        assertThat(OrchestrationIds.ORCHESTRATION_RESULT_NS)
                .isEqualTo(UUID.fromString("ad694481-13ed-40ec-9816-645cfe953e2f"));
        assertThat(OrchestrationIds.ORCHESTRATION_EVENT_NS)
                .isEqualTo(UUID.fromString("97ebe21e-037e-4b3a-adb0-8b57c166e4d5"));
        assertThat(OrchestrationIds.ORCHESTRATION_SCHEDULING_NS)
                .isEqualTo(UUID.fromString("a5ada7e8-3b1a-4d9c-a970-ec22f1f74bd2"));
        assertThat(OrchestrationIds.WORKSPACE_ACK_NS)
                .isEqualTo(UUID.fromString("3c8ea127-18cb-465f-8edb-025ec79ef52a"));
    }

    @Test
    @DisplayName("uuidV5 matches the RFC 4122 DNS-namespace example (TS parity vector)")
    void uuidV5MatchesRfcVector() {
        // Same vector as constants.test.ts — cross-language parity proof.
        UUID id = OrchestrationIds.uuidV5(
                UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "example.com");
        assertThat(id.toString()).isEqualTo("cfbff0d1-9375-5685-968c-48ce8b15ae17");
    }

    @Test
    @DisplayName("scheduling-event shape is deterministic (run:task:episode:occurrence)")
    void schedulingEventIdIsDeterministic() {
        UUID runId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID taskId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID a = OrchestrationIds.schedulingEventId(runId, taskId, 1, 3);
        UUID b = OrchestrationIds.schedulingEventId(runId, taskId, 1, 3);
        UUID c = OrchestrationIds.schedulingEventId(runId, taskId, 1, 4);
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("acknowledgement ID shape (releaseId:generation:status)")
    void acknowledgementIdIsDeterministic() {
        UUID releaseId = UUID.randomUUID();
        UUID a = OrchestrationIds.acknowledgementId(releaseId, 2, "RELEASED");
        UUID b = OrchestrationIds.acknowledgementId(releaseId, 2, "RELEASED");
        UUID lost = OrchestrationIds.acknowledgementId(releaseId, 2, "LOST");
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(lost);
    }

    @Test
    @DisplayName("sha256Hex produces lowercase canonical hex")
    void sha256HexIsCanonical() {
        String digest = OrchestrationIds.sha256Hex("hello".getBytes());
        assertThat(digest).isEqualTo(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(digest).hasSize(64);
    }
}
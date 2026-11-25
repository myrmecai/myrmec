// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #8a — verifies the {@link ContextSummaryMarker} audit record: the
 * dispatch-time {@code of(...)} factory, the completion-time
 * {@link ContextSummaryMarker#withCompletion(String, Integer)} enrichment, the
 * JSON round-trip that lands in {@code payload_json}, and backward-compatible
 * deserialization of legacy rows that predate the audit fields.
 */
class ContextSummaryMarkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ofStampsCoverageAndTriggerLeavingCompletionFieldsNull() {
        ContextSummaryMarker marker = ContextSummaryMarker.of(
                3L, 17L, 15, ContextSummaryMarker.Trigger.AUTO);

        assertThat(marker.kind()).isEqualTo(ContextSummaryMarker.KIND);
        assertThat(marker.coversFromSequenceNo()).isEqualTo(3L);
        assertThat(marker.coversUpToSequenceNo()).isEqualTo(17L);
        assertThat(marker.summarizedMessageCount()).isEqualTo(15);
        assertThat(marker.trigger()).isEqualTo(ContextSummaryMarker.Trigger.AUTO);
        assertThat(marker.modelCode()).isNull();
        assertThat(marker.tokenCount()).isNull();
    }

    @Test
    void withCompletionFillsModelAndTokensWithoutLosingCoverage() {
        ContextSummaryMarker enriched = ContextSummaryMarker
                .of(0L, 9L, 10, ContextSummaryMarker.Trigger.EXPLICIT)
                .withCompletion("github-gpt-4o", 412);

        assertThat(enriched.coversFromSequenceNo()).isZero();
        assertThat(enriched.coversUpToSequenceNo()).isEqualTo(9L);
        assertThat(enriched.summarizedMessageCount()).isEqualTo(10);
        assertThat(enriched.trigger()).isEqualTo(ContextSummaryMarker.Trigger.EXPLICIT);
        assertThat(enriched.modelCode()).isEqualTo("github-gpt-4o");
        assertThat(enriched.tokenCount()).isEqualTo(412);
    }

    @Test
    void serialisesAndDeserialisesTheFullAuditRecord() throws Exception {
        ContextSummaryMarker marker = ContextSummaryMarker
                .of(0L, 9L, 10, ContextSummaryMarker.Trigger.AUTO)
                .withCompletion("github-gpt-4o", 412);

        String json = objectMapper.writeValueAsString(marker);
        ContextSummaryMarker back = objectMapper.readValue(json, ContextSummaryMarker.class);

        assertThat(back).isEqualTo(marker);
        assertThat(json).contains("\"trigger\":\"AUTO\"");
        assertThat(json).contains("\"modelCode\":\"github-gpt-4o\"");
    }

    @Test
    void legacyRowWithoutAuditFieldsDeserialisesWithNulls() throws Exception {
        // A CONTEXT_SUMMARY row written before #8a carried only kind + coverage.
        String legacy = "{\"kind\":\"CONTEXT_SUMMARY\",\"coversUpToSequenceNo\":4,"
                + "\"summarizedMessageCount\":5}";

        ContextSummaryMarker back = objectMapper.readValue(legacy, ContextSummaryMarker.class);

        assertThat(back.coversUpToSequenceNo()).isEqualTo(4L);
        assertThat(back.summarizedMessageCount()).isEqualTo(5);
        assertThat(back.coversFromSequenceNo()).isNull();
        assertThat(back.trigger()).isNull();
        assertThat(back.modelCode()).isNull();
        assertThat(back.tokenCount()).isNull();
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * §4 GET /interactions item: the redacted transcript projection. The row's
 * text rides ONLY after redaction (the sweeper's CONTENT_EXPIRED markers
 * are what retention-swept rows carry); identity/status/usage metadata is
 * always present (§3.5).
 */
public final class InteractionTranscriptPage {

    private InteractionTranscriptPage() {
    }

    /** One §4 transcript item (camelCase JSON). */
    public static Map<String, Object> itemOf(ExecutionInteraction row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("interactionId", String.valueOf(row.getId()));
        item.put("ordinal", row.getOrdinal());
        item.put("status", row.getStatus() == null ? null : row.getStatus().name());
        item.put("requestText", row.getRequestText());
        item.put("answerText", row.getAnswerText());
        item.put("usage", row.getUsage());
        item.put("usageStatus", row.getUsageStatus());
        item.put("error", row.getError());
        item.put("responseDeadline", iso(row.getResponseDeadline()));
        item.put("acceptedAt", iso(row.getAcceptedAt()));
        item.put("completedAt", iso(row.getCompletedAt()));
        return item;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
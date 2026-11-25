// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._protocol.payload;

import ai.myrmec.engine.inference.InferenceMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * execution.start payload (§8.1) — the V2 agent-channel shape (mirrors the
 * SDK's {@code executionStartPayloadSchema} in {@code agentFrames.ts}).
 *
 * <p>The agent channel identifies a turn by the envelope's {@code turn}
 * (the engine's per-session {@code sequenceNo}); the payload carries only
 * the input block + deadline + optional resume. There is no executionId /
 * sessionId / toolPolicy / output here — those V1 host-channel fields are
 * gone (tools ride {@code session.open.profile.tools}; output policy is
 * implicit in the turn).</p>
 */
public record ExecutionStartPayload(
        Input input,        // null for orchestration (assignment installed at session.open)
        Instant deadline,
        Resume resume) {    // non-null when resuming a paused turn (ask answer / approval decision)

    public record Input(
            List<InferenceMessage> messages,   // reuse the legacy §5.3 message record — same shape
            List<PinnedMessage> pins) {         // nullable: engine-pinned rows injected every call

        public record PinnedMessage(String messageId, String content, String label) {}
    }

    /** §8.1 resume block (SDK {@code executionStartPayloadSchema.resume}): resumes
     *  a paused turn. Exactly one of {@code decision} (approval) or {@code answer}
     *  (ask) rides; {@code turn} is the paused turn, {@code digest} binds the
     *  resume to the pending action/question. */
    public record Resume(
            Integer turn,
            String decision,
            String message,
            AskAnswer answer,
            String digest) {}

    /** The ask answer union (SDK {@code askAnswerSchema}). */
    public record AskAnswer(String kind, java.util.List<String> ids, String text) {}
}

// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.inference.InferenceMessage;

import java.util.List;

/**
 * Transcript composer â€” the seam where workflow â‰  conversation (Â§6.3).
 *
 * <p>Each implementation ports the agent's assembly logic byte-for-byte
 * into the engine. The resulting {@link InferenceMessage} list is the
 * fully assembled transcript that the agent maps straight to LangChain.</p>
 */
public interface TranscriptComposer {

    /**
     * Compose the full transcript for a single turn/step.
     *
     * @param spec the request spec carrying service-specific inputs
     * @return the assembled messages (system + history + user)
     */
    List<InferenceMessage> compose(InferenceRequestSpec spec);
}
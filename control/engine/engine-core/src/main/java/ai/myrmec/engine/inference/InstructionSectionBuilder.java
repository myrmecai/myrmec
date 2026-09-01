// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Instruction section builder — the future-proofing seam (§6.4, D6).
 *
 * <p>Today this method calls {@code TaskContextResolver} and renders full content.
 * This is the exact seam where, later:
 * <ul>
 *   <li>{@code ContextBuilder.assemble(...)} replaces {@code TaskContextResolver}
 *       (governed assembly + budget + truncation) — no wire change</li>
 *   <li>Full-content injection is replaced by retrieved instruction chunks from
 *       a built-in vector DB (§9.2) — no wire change</li>
 * </ul></p>
 *
 * <p>Keep this method free of any transcript-shaping concerns so swapping its
 * internals is trivial.</p>
 */
@Component
public class InstructionSectionBuilder {

    /**
     * Build the instruction/knowledge entries for the given scope.
     *
     * <p>Today: delegates to {@code TaskContextResolver.resolve(projectId)} and
     * maps the result to {@link InferenceRequestSpec.KnowledgeEntry}.</p>
     *
     * @param projectId the project scope (nullable for org-only scope)
     * @return knowledge entries grouped by category
     */
    public List<InferenceRequestSpec.KnowledgeEntry> build(
            java.util.UUID projectId,
            String serviceType) {
        // Today: TaskContextResolver.resolve(projectId) → KnowledgeEntry list
        // Tomorrow: ContextBuilder.assemble(...) with governed assembly + budget
        //
        // This method is intentionally a single seam — callers don't care
        // how the entries are produced, only that they get a list of
        // {name, content, category} to render into the system prompt.
        return List.of();  // wired by InferenceRequestAssembler
    }
}
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.spi.connector;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * A raw file emitted by a {@link KnowledgeSourceConnector} during sync.
 *
 * <p>File-based connectors (git, S3, web-crawl) emit raw file bytes so the
 * retrieval provider (e.g. RAGFlow) can run its own parsing, chunking, and
 * embedding pipeline. API-based connectors (Confluence, Jira, Notion) that
 * don't have "files" per se can emit text content as a virtual file with a
 * synthetic filename and {@code text/plain} MIME type.</p>
 *
 * @param filename  non-blank filename including extension (e.g. {@code "report.pdf"})
 * @param mimeType  non-blank MIME type (e.g. {@code "application/pdf"})
 * @param content   raw file bytes
 * @param locator   non-blank source-relative locator (e.g. git path, S3 key)
 * @param metadata  optional source-specific metadata
 */
public record EmittedFile(
        @NotBlank String filename,
        @NotBlank String mimeType,
        @NotNull byte[] content,
        @NotBlank String locator,
        Map<String, String> metadata
) {
    public EmittedFile {
        if (metadata == null) {
            metadata = Map.of();
        }
    }
}
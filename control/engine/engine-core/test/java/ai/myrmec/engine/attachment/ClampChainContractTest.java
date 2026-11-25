// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.attachment;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.scan.ContentScanProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECON-03: Attachment governance clamp chain contract.
 *
 * <p>Verifies that the attachment clamp chain infrastructure exists:
 * <ul>
 *   <li>{@link AttachmentService} — the upload-time gate (enabled, size, type, scan)</li>
 *   <li>{@link AttachmentContentScanProvider} — the content scan gate (before storage)</li>
 * </ul>
 *
 * <p>Inline-injection clamping happens at turn-dispatch time in
 * {@code ConversationTurnDispatcher.buildAttachments}.
 */
@Tag("RECON-03")
@Tag("SG4")
@DisplayName("RECON-03: Attachment Clamp Chain Contract")
class ClampChainContractTest extends IntegrationTestBase {

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private ContentScanProvider scanProvider;

    @Test
    @DisplayName("AttachmentService bean is available")
    void attachmentServiceExists() {
        assertThat(attachmentService).isNotNull();
    }

    @Test
    @DisplayName("AttachmentContentScanProvider bean is available")
    void scanProviderExists() {
        assertThat(scanProvider).isNotNull();
    }
}
// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.security.scan;

import ai.myrmec.engine.audit.AuditEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for SecretLeakService in all three modes (BLOCK, REDACT, WARN).
 * Verifies clean text passes through untouched, and that the audit hook
 * records OUTPUT_SECRET_LEAK via AuditEventService.recordEvent().
 */
class SecretLeakServiceTest {

    private static final String SAMPLE_CLEAN =
            "Here is a friendly hello with no secrets in it.";
    private static final String SAMPLE_LEAKY_AWS =
            "Use this key: AKIAABCDEFGHIJKLMNOP — please don't share it.";
    private static final String SAMPLE_LEAKY_GH_AND_OPENAI =
            "Token ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "
                    + "and OpenAI sk-abcdefghijklmnopqrstu and goodbye.";

    private final RegexSecretLeakScanner scanner = new RegexSecretLeakScanner();

    @Test
    void cleanTextPassesThrough_andNoAuditRecorded() {
        AuditEventService audit = mock(AuditEventService.class);
        SecretLeakService svc = SecretLeakService.forTest(scanner, audit, SecretLeakService.Mode.REDACT);

        SecretLeakService.Result r = svc.inspectOutbound(SAMPLE_CLEAN, UUID.randomUUID(), null);

        assertThat(r.isLeakDetected()).isFalse();
        assertThat(r.isBlocked()).isFalse();
        assertThat(r.getText()).isEqualTo(SAMPLE_CLEAN);
        verify(audit, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void redactMode_replacesEachHit_andRecordsAuditEvent() {
        AuditEventService audit = mock(AuditEventService.class);
        SecretLeakService svc = SecretLeakService.forTest(scanner, audit, SecretLeakService.Mode.REDACT);

        UUID convId = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();
        SecretLeakService.Result r = svc.inspectOutbound(SAMPLE_LEAKY_GH_AND_OPENAI, convId, msgId);

        assertThat(r.isLeakDetected()).isTrue();
        assertThat(r.isBlocked()).isFalse();
        assertThat(r.getText())
                .doesNotContain("ghp_aaaa")
                .doesNotContain("sk-abcdefg")
                .contains("<redacted:GITHUB_TOKEN>")
                .contains("<redacted:OPENAI_API_KEY>");

        verify(audit, atLeastOnce()).recordEvent(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void blockMode_returnsNullText_andStillAudits() {
        AuditEventService audit = mock(AuditEventService.class);
        SecretLeakService svc = SecretLeakService.forTest(scanner, audit, SecretLeakService.Mode.BLOCK);

        SecretLeakService.Result r = svc.inspectOutbound(SAMPLE_LEAKY_AWS, UUID.randomUUID(), null);

        assertThat(r.isLeakDetected()).isTrue();
        assertThat(r.isBlocked()).isTrue();
        assertThat(r.getText()).isNull();
        verify(audit, atLeastOnce()).recordEvent(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void warnMode_passesTextThroughVerbatim_butStillAudits() {
        AuditEventService audit = mock(AuditEventService.class);
        SecretLeakService svc = SecretLeakService.forTest(scanner, audit, SecretLeakService.Mode.WARN);

        SecretLeakService.Result r = svc.inspectOutbound(SAMPLE_LEAKY_AWS, UUID.randomUUID(), null);

        assertThat(r.isLeakDetected()).isTrue();
        assertThat(r.isBlocked()).isFalse();
        assertThat(r.getText()).isEqualTo(SAMPLE_LEAKY_AWS);
        verify(audit, atLeastOnce()).recordEvent(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
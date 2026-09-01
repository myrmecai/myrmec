// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import ai.myrmec.engine.secret.CredentialType;
import ai.myrmec.engine.secret.LocalSecretBackendAdapter;
import ai.myrmec.engine.secret.Secret;
import ai.myrmec.engine.secret.SecretBackend;
import ai.myrmec.engine.secret.SecretPayload;
import ai.myrmec.engine.secret.SecretRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Bootstrap component to auto-create the audit HMAC key on startup.
 *
 * <p>On first startup, if no global secret named {@code audit_hmac_key}
 * exists, a 256-bit random key is generated, stored as a
 * {@link CredentialType#SECRET_KEY} global secret (AES-256-GCM encrypted),
 * and the cleartext is cached for use by {@link AuditHashChainService}.
 *
 * <p>On subsequent startups, the existing key is resolved and cached.
 * The key never changes after creation — rotation is an EE V1.1 feature.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditHmacKeyBootstrap {

    private final SecretRepository secretRepository;
    private final LocalSecretBackendAdapter localBackendAdapter;

    /** Cached HMAC key bytes — resolved once on startup. */
    private byte[] hmacKey;

    /**
     * Get the cached HMAC key. Available after {@link #onApplicationReady}.
     */
    public byte[] getHmacKey() {
        if (hmacKey == null) {
            throw new IllegalStateException("Audit HMAC key not initialized — startup incomplete");
        }
        return hmacKey;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Secret existing = secretRepository.findGlobalByName(AuditHashChainService.AUDIT_HMAC_KEY_NAME)
                .orElse(null);

        if (existing != null) {
            // Resolve existing key
            SecretPayload payload = localBackendAdapter.read(existing);
            if (payload instanceof SecretPayload.SecretKey sk) {
                hmacKey = sk.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                log.info("Audit HMAC key resolved from secrets vault (secret id={})", existing.getId());
            } else {
                throw new IllegalStateException(
                        "Existing audit_hmac_key secret has wrong type: " + payload.getClass());
            }
        } else {
            // Generate and store a new 256-bit key
            byte[] keyBytes = new byte[32]; // 256 bits
            new SecureRandom().nextBytes(keyBytes);
            String keyBase64 = Base64.getEncoder().encodeToString(keyBytes);

            Secret secret = new Secret();
            secret.setName(AuditHashChainService.AUDIT_HMAC_KEY_NAME);
            secret.setType(CredentialType.SECRET_KEY);
            secret.setBackend(SecretBackend.LOCAL);
            localBackendAdapter.write(secret, new SecretPayload.SecretKey(keyBase64));
            secret = secretRepository.save(secret);

            hmacKey = keyBase64.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            log.info("Audit HMAC key auto-created in secrets vault (secret id={})", secret.getId());
        }
    }
}
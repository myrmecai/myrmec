package ai.myrmec.engine.secret.dto;

import ai.myrmec.engine.secret.SecretPayload;

/**
 * Update payload — only the credential value can change.
 *
 * <p>Name and {@code type} are immutable after creation; consumers reference
 * secrets by UUID and rely on type stability. To re-type a secret, delete and
 * re-create.
 */
public record UpdateSecretRequest(SecretPayload payload) {
}

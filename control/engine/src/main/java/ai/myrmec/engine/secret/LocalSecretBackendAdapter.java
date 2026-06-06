package ai.myrmec.engine.secret;

import ai.myrmec.engine._system.crypto.EncryptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Default backend: payload is JSON-encoded, AES-256-GCM encrypted by
 * {@link EncryptionService} and stored in {@code secrets.encrypted_value}.
 */
@Component
@RequiredArgsConstructor
public class LocalSecretBackendAdapter implements SecretBackendAdapter {

    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    @Override
    public SecretBackend backend() {
        return SecretBackend.LOCAL;
    }

    @Override
    public void write(Secret secret, SecretPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            secret.setEncryptedValue(encryptionService.encrypt(json));
            secret.setBackendRef(null);
            secret.setBackend(SecretBackend.LOCAL);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialise secret payload", e);
        }
    }

    @Override
    public SecretPayload read(Secret secret) {
        if (secret.getEncryptedValue() == null) {
            throw new IllegalStateException("LOCAL secret " + secret.getId() + " has no ciphertext");
        }
        try {
            String json = encryptionService.decrypt(secret.getEncryptedValue());
            return objectMapper.readValue(json, SecretPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialise secret payload for " + secret.getId(), e);
        }
    }
}

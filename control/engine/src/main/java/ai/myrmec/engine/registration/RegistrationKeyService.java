package ai.myrmec.engine.registration;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationKeyService {

    private static final String KEY_PREFIX = "myr_agent_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RegistrationKeyRepository repository;

    /**
     * Create a new registration key.
     * Only the hash is stored; plaintext is returned once and never persisted.
     * 
     * @return the plaintext key (only returned once, never stored)
     */
    @Transactional
    public RegistrationKeyResult createKey(String label, Integer expirationDays) {
        String keyValue = generateKeyValue();
        String keyHash = hash(keyValue);

        RegistrationKey key = new RegistrationKey();
        key.setKeyHash(keyHash);
        key.setLabel(label);

        if (expirationDays != null && expirationDays > 0) {
            key.setExpiresAt(Instant.now().plus(expirationDays, ChronoUnit.DAYS));
        }

        key = repository.save(key);
        
        // Log only the prefix, never the full key
        log.info("Created registration key: {}*** (label: {})", KEY_PREFIX, label);
        
        return new RegistrationKeyResult(key, keyValue);
    }

    /**
     * Find a registration key by its plaintext value.
     * Hashes the input and looks up by hash.
     */
    @Transactional(readOnly = true)
    public Optional<RegistrationKey> findByKeyValue(String keyValue) {
        String keyHash = hash(keyValue);
        return repository.findByKeyHash(keyHash);
    }

    /**
     * Find a registration key by its ID.
     */
    @Transactional(readOnly = true)
    public Optional<RegistrationKey> findById(java.util.UUID keyId) {
        return repository.findById(keyId);
    }

    /**
     * SHA-256 hash for key storage/lookup.
     */
    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Revoke a registration key by its ID.
     */
    @Transactional
    public void revokeKey(java.util.UUID keyId) {
        RegistrationKey key = repository.findById(keyId)
                .orElseThrow(() -> ResourceNotFoundException.registrationKey(keyId));

        key.setIsRevoked(true);
        repository.save(key);
        log.info("Revoked registration key: {}", keyId);
    }

    private String generateKeyValue() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return KEY_PREFIX + encoded;
    }

    /**
     * Result containing both the entity and the plaintext key (returned only on creation).
     */
    public record RegistrationKeyResult(RegistrationKey entity, String plaintextKey) {}
}

package ai.myrmec.engine.secret;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side facade used by feature code (knowledge fetcher, workflow tools, ...)
 * to dereference a secret UUID into its decrypted {@link SecretPayload}.
 *
 * <p>Always scope by project: a project-scoped secret from one project must not
 * leak into another. Global secrets are visible to every project but can only
 * be authored by SYSTEM_ADMIN.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecretResolverService {

    private final SecretRepository secretRepository;
    private final SecretBackendRegistry backendRegistry;

    @Transactional(readOnly = true)
    public Optional<Secret> findResolvable(UUID secretId, UUID projectId) {
        if (secretId == null) {
            return Optional.empty();
        }
        return secretRepository.findResolvable(secretId, projectId);
    }

    /** Resolve and decrypt; throws if the secret is missing or unreachable. */
    @Transactional(readOnly = true)
    public SecretPayload resolve(UUID secretId, UUID projectId) {
        Secret secret = secretRepository.findResolvable(secretId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Secret", secretId.toString()));
        return backendRegistry.forSecret(secret).read(secret);
    }

    /**
     * Resolve and downcast to a specific payload subtype.
     *
     * @throws SecretTypeMismatchException if the secret has a different type
     */
    @Transactional(readOnly = true)
    public <T extends SecretPayload> T resolveAs(UUID secretId, UUID projectId, Class<T> expected) {
        SecretPayload payload = resolve(secretId, projectId);
        if (!expected.isInstance(payload)) {
            throw new SecretTypeMismatchException(
                    "Secret " + secretId + " has type " + payload.type()
                            + " but " + expected.getSimpleName() + " was required");
        }
        return expected.cast(payload);
    }

    /**
     * Resolve and verify the secret's {@link CredentialType} is in the accepted set.
     *
     * @throws SecretTypeMismatchException when the type is not acceptable
     */
    @Transactional(readOnly = true)
    public SecretPayload resolveOneOf(UUID secretId, UUID projectId, CredentialType... acceptable) {
        SecretPayload payload = resolve(secretId, projectId);
        if (Arrays.stream(acceptable).noneMatch(t -> t == payload.type())) {
            throw new SecretTypeMismatchException(
                    "Secret " + secretId + " has type " + payload.type()
                            + " but one of " + Arrays.toString(acceptable) + " was required");
        }
        return payload;
    }
}

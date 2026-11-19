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
 * <p>Always scope by Team: a Team-scoped secret from one Team must not
 * leak into another. Global secrets are visible to every Team but can only
 * be authored by SYSTEM_ADMIN.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecretResolverService {

    private final SecretRepository secretRepository;
    private final SecretBackendRegistry backendRegistry;

    @Transactional(readOnly = true)
    public Optional<Secret> findResolvable(UUID secretId, UUID teamId) {
        if (secretId == null) {
            return Optional.empty();
        }
        return secretRepository.findResolvable(secretId, teamId);
    }

    /** Resolve and decrypt; throws if the secret is missing or unreachable. */
    @Transactional(readOnly = true)
    public SecretPayload resolve(UUID secretId, UUID teamId) {
        Secret secret = secretRepository.findResolvable(secretId, teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Secret", secretId.toString()));
        return backendRegistry.forSecret(secret).read(secret);
    }

    /**
     * Resolve and downcast to a specific payload subtype.
     *
     * @throws SecretTypeMismatchException if the secret has a different type
     */
    @Transactional(readOnly = true)
    public <T extends SecretPayload> T resolveAs(UUID secretId, UUID teamId, Class<T> expected) {
        SecretPayload payload = resolve(secretId, teamId);
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
    public SecretPayload resolveOneOf(UUID secretId, UUID teamId, CredentialType... acceptable) {
        SecretPayload payload = resolve(secretId, teamId);
        if (Arrays.stream(acceptable).noneMatch(t -> t == payload.type())) {
            throw new SecretTypeMismatchException(
                    "Secret " + secretId + " has type " + payload.type()
                            + " but one of " + Arrays.toString(acceptable) + " was required");
        }
        return payload;
    }

    /**
     * Resolve a free-form secret <em>reference</em> — either a secret UUID or a
     * secret name — to its primary plaintext string, scoped to {@code teamId}.
     *
     * <p>Used by connectors whose config carries an opaque secret token (e.g. a
     * git source's {@code tokenSecret}). Resolution order:</p>
     * <ol>
     *   <li>If the reference parses as a UUID, resolve it directly (Team or
     *       global) via {@link SecretRepository#findResolvable}.</li>
     *   <li>Otherwise treat it as a name: prefer a Team-scoped secret, then
     *       fall back to a global secret of that name.</li>
     * </ol>
     *
     * <p>Returns {@link Optional#empty()} when the reference is blank, does not
     * resolve to a secret reachable from {@code teamId}, or the secret has no
     * single primary string (e.g. a {@code CUSTOM} payload). Cross-Team
     * leakage is prevented because every lookup is constrained to globals or the
     * given Team.</p>
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveReferenceString(String reference, UUID teamId) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        return findByReference(reference.trim(), teamId)
                .map(secret -> backendRegistry.forSecret(secret).read(secret))
                .flatMap(SecretResolverService::primaryString);
    }

    private Optional<Secret> findByReference(String reference, UUID teamId) {
        UUID id = tryParseUuid(reference);
        if (id != null) {
            return secretRepository.findResolvable(id, teamId);
        }
        if (teamId != null) {
            Optional<Secret> scoped = secretRepository.findByTeamIdAndName(teamId, reference);
            if (scoped.isPresent()) {
                return scoped;
            }
        }
        return secretRepository.findGlobalByName(reference);
    }

    private static UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The single most meaningful plaintext string for an opaque-token consumer. */
    private static Optional<String> primaryString(SecretPayload payload) {
        return Optional.ofNullable(switch (payload) {
            case SecretPayload.BearerToken b -> b.token();
            case SecretPayload.SecretKey s -> s.secret();
            case SecretPayload.ApiKey a -> a.key();
            case SecretPayload.UsernamePassword u -> u.password();
            case SecretPayload.OAuthClient o -> o.clientSecret();
            case SecretPayload.SslPrivateKey k -> k.privateKey();
            case SecretPayload.CustomPayload ignored -> null;
        });
    }
}

package ai.myrmec.engine.user;

import ai.myrmec.engine._system.crypto.EncryptionService;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.user.dto.AuthProviderCreateRequest;
import ai.myrmec.engine.user.dto.AuthProviderUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthProviderService {

    private static final String CLIENT_SECRET_KEY = "clientSecret";
    private static final String CLIENT_SECRET_ENCRYPTED_KEY = "clientSecretEncrypted";

    private final AuthenticationProviderRepository authenticationProviderRepository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;

    public record ReencryptSecretsResult(int scannedProviders, int reencryptedProviders, int skippedProviders) {
    }

    @Transactional(readOnly = true)
    public List<AuthenticationProvider> findAll() {
        return authenticationProviderRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<AuthenticationProvider> findEnabled() {
        return authenticationProviderRepository.findByIsEnabledTrueOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public AuthenticationProvider findByCode(String code) {
        return authenticationProviderRepository.findById(normalizeCode(code))
                .orElseThrow(() -> ResourceNotFoundException.of("AuthenticationProvider", "code", code));
    }

    @Transactional
    public AuthenticationProvider create(AuthProviderCreateRequest request) {
        String normalizedCode = normalizeCode(request.getCode());
        if (authenticationProviderRepository.existsById(normalizedCode)) {
            throw BadRequestException.forField("code", "DUPLICATE", "Provider code already exists");
        }

        AuthenticationProvider provider = new AuthenticationProvider();
        provider.setCode(normalizedCode);
        provider.setProviderType(request.getProviderType());
        provider.setName(request.getName().trim());
        provider.setIsEnabled(Boolean.TRUE.equals(request.getIsEnabled()));
        provider.setIsSystem(false);
        provider.setMetadata(prepareMetadataForStorage(request.getMetadata(), null));

        validateTransition(provider, provider.getIsEnabled());
        AuthenticationProvider saved = authenticationProviderRepository.save(provider);
        log.info("Created auth provider: {} ({})", saved.getCode(), saved.getProviderType());
        return saved;
    }

    @Transactional
    public AuthenticationProvider update(String code, AuthProviderUpdateRequest request) {
        AuthenticationProvider provider = findByCode(code);

        if (request.getName() != null && !request.getName().isBlank()) {
            provider.setName(request.getName().trim());
        }

        if (request.getMetadata() != null) {
            provider.setMetadata(prepareMetadataForStorage(request.getMetadata(), provider.getMetadata()));
        }

        if (request.getIsEnabled() != null) {
            validateTransition(provider, request.getIsEnabled());
            provider.setIsEnabled(request.getIsEnabled());
        }

        AuthenticationProvider saved = authenticationProviderRepository.save(provider);
        log.info("Updated auth provider: {} (enabled: {})", saved.getCode(), saved.getIsEnabled());
        return saved;
    }

    @Transactional
    public AuthenticationProvider enable(String code) {
        AuthenticationProvider provider = findByCode(code);
        validateTransition(provider, true);
        provider.setIsEnabled(true);
        AuthenticationProvider saved = authenticationProviderRepository.save(provider);
        log.info("Enabled auth provider: {}", saved.getCode());
        return saved;
    }

    @Transactional
    public AuthenticationProvider disable(String code) {
        AuthenticationProvider provider = findByCode(code);
        validateTransition(provider, false);
        provider.setIsEnabled(false);
        AuthenticationProvider saved = authenticationProviderRepository.save(provider);
        log.info("Disabled auth provider: {}", saved.getCode());
        return saved;
    }

    @Transactional(readOnly = true)
    public void validateProviderEnabledForUser(String providerCode) {
        AuthenticationProvider provider = findByCode(providerCode);
        if (!Boolean.TRUE.equals(provider.getIsEnabled())) {
            throw new BadRequestException("Authentication provider is disabled");
        }
    }

    @Transactional(readOnly = true)
    public void validateProviderExistsForUser(String providerCode) {
        findByCode(providerCode);
    }

    @Transactional(readOnly = true)
    public AuthenticationProvider getEnabledExternalProvider(String providerCode) {
        AuthenticationProvider provider = findByCode(providerCode);
        if (!Boolean.TRUE.equals(provider.getIsEnabled())) {
            throw new BadRequestException("Authentication provider is disabled");
        }
        if (provider.getProviderType() == AuthenticationProvider.ProviderType.LOCAL) {
            throw new BadRequestException("LOCAL provider does not support external login flow");
        }
        return provider;
    }

    @Transactional
    public ReencryptSecretsResult reencryptStoredClientSecrets() {
        List<AuthenticationProvider> providers = authenticationProviderRepository.findAll();
        List<AuthenticationProvider> providersToUpdate = new ArrayList<>();

        int reencrypted = 0;
        int skipped = 0;

        for (AuthenticationProvider provider : providers) {
            Map<String, Object> metadata = provider.getMetadata();
            if (metadata == null) {
                skipped++;
                continue;
            }

            Object encryptedValue = metadata.get(CLIENT_SECRET_ENCRYPTED_KEY);
            if (!(encryptedValue instanceof String encryptedSecret) || encryptedSecret.isBlank()) {
                skipped++;
                continue;
            }

            String decrypted = encryptionService.decrypt(Base64.getDecoder().decode(encryptedSecret));
            byte[] reencryptedValue = encryptionService.encrypt(decrypted);
            metadata.put(CLIENT_SECRET_ENCRYPTED_KEY, Base64.getEncoder().encodeToString(reencryptedValue));

            providersToUpdate.add(provider);
            reencrypted++;
        }

        if (!providersToUpdate.isEmpty()) {
            authenticationProviderRepository.saveAll(providersToUpdate);
        }

        log.info("Re-encrypted auth provider client secrets (scanned: {}, re-encrypted: {}, skipped: {})",
                providers.size(), reencrypted, skipped);
        return new ReencryptSecretsResult(providers.size(), reencrypted, skipped);
    }

    private void validateTransition(AuthenticationProvider provider, boolean targetEnabledState) {
        if (targetEnabledState) {
            return;
        }

        // Protect bootstrap admin account path: LOCAL must remain enabled while any system user is LOCAL-mapped.
        if (provider.isLocal() && userRepository.existsByIsSystemTrueAndProviderCode(AuthenticationProvider.LOCAL_CODE)) {
            throw new BadRequestException("LOCAL provider cannot be disabled while bootstrap admin remains LOCAL");
        }

        // Keep at least one enabled provider available.
        if (Boolean.TRUE.equals(provider.getIsEnabled())) {
            long enabledCount = authenticationProviderRepository.findByIsEnabledTrueOrderByCodeAsc().size();
            if (enabledCount <= 1) {
                throw new BadRequestException("At least one authentication provider must remain enabled");
            }
        }
    }

    private String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    Map<String, Object> prepareMetadataForStorage(Map<String, Object> requestedMetadata,
                                                  Map<String, Object> existingMetadata) {
        Map<String, Object> merged = new HashMap<>();

        if (existingMetadata != null) {
            merged.putAll(existingMetadata);
        }

        if (requestedMetadata == null) {
            return merged;
        }

        merged.putAll(requestedMetadata);

        Object clientSecretValue = requestedMetadata.get(CLIENT_SECRET_KEY);
        if (clientSecretValue instanceof String clientSecret && !clientSecret.isBlank()) {
            byte[] encrypted = encryptionService.encrypt(clientSecret);
            merged.put(CLIENT_SECRET_ENCRYPTED_KEY, Base64.getEncoder().encodeToString(encrypted));
        }

        // Never persist plaintext client secret.
        merged.remove(CLIENT_SECRET_KEY);
        return merged;
    }
}

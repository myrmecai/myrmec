package ai.myrmec.engine.user.dto;

import ai.myrmec.engine.user.AuthenticationProvider;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class AuthProviderResponse {

    private static final String CLIENT_SECRET_KEY = "clientSecret";
    private static final String CLIENT_SECRET_ENCRYPTED_KEY = "clientSecretEncrypted";
    private static final String CLIENT_SECRET_CONFIGURED_KEY = "clientSecretConfigured";

    private String code;
    private AuthenticationProvider.ProviderType providerType;
    private String name;
    private Boolean isEnabled;
    private Boolean isSystem;
    private Map<String, Object> metadata;
    private Instant createdAt;
    private Instant updatedAt;

    public static AuthProviderResponse from(AuthenticationProvider provider) {
        Map<String, Object> metadata = new HashMap<>();
        if (provider.getMetadata() != null) {
            metadata.putAll(provider.getMetadata());
        }

        boolean hasSecret = metadata.containsKey(CLIENT_SECRET_ENCRYPTED_KEY) || metadata.containsKey(CLIENT_SECRET_KEY);
        metadata.remove(CLIENT_SECRET_ENCRYPTED_KEY);
        metadata.remove(CLIENT_SECRET_KEY);
        if (hasSecret) {
            metadata.put(CLIENT_SECRET_CONFIGURED_KEY, true);
        }

        return AuthProviderResponse.builder()
                .code(provider.getCode())
                .providerType(provider.getProviderType())
                .name(provider.getName())
                .isEnabled(provider.getIsEnabled())
                .isSystem(provider.getIsSystem())
                .metadata(metadata)
                .createdAt(provider.getCreatedAt())
                .updatedAt(provider.getUpdatedAt())
                .build();
    }
}

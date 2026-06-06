package ai.myrmec.engine.user.dto;

import ai.myrmec.engine.user.AuthProviderService;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthProviderSecretReencryptResponse {

    private int scannedProviders;
    private int reencryptedProviders;
    private int skippedProviders;

    public static AuthProviderSecretReencryptResponse from(AuthProviderService.ReencryptSecretsResult result) {
        return AuthProviderSecretReencryptResponse.builder()
                .scannedProviders(result.scannedProviders())
                .reencryptedProviders(result.reencryptedProviders())
                .skippedProviders(result.skippedProviders())
                .build();
    }
}

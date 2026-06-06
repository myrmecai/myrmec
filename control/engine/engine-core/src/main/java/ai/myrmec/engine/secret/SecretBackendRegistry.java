package ai.myrmec.engine.secret;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Looks up the right {@link SecretBackendAdapter} for a {@link Secret}. */
@Component
@RequiredArgsConstructor
public class SecretBackendRegistry {

    private final List<SecretBackendAdapter> adapters;
    private final Map<SecretBackend, SecretBackendAdapter> byBackend = new EnumMap<>(SecretBackend.class);

    @PostConstruct
    void init() {
        for (SecretBackendAdapter adapter : adapters) {
            byBackend.put(adapter.backend(), adapter);
        }
    }

    public SecretBackendAdapter forSecret(Secret secret) {
        SecretBackendAdapter adapter = byBackend.get(secret.getBackend());
        if (adapter == null) {
            throw new IllegalStateException(
                    "No SecretBackendAdapter registered for backend " + secret.getBackend());
        }
        return adapter;
    }
}

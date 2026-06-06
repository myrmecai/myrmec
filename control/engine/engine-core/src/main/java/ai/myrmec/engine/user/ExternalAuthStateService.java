package ai.myrmec.engine.user;

import ai.myrmec.engine._system.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ExternalAuthStateService {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final Map<String, StateRecord> stateStore = new ConcurrentHashMap<>();

    public String issueState(String providerCode) {
        purgeExpired();
        String state = UUID.randomUUID().toString();
        stateStore.put(state, new StateRecord(providerCode, Instant.now().plus(STATE_TTL)));
        return state;
    }

    public void validateAndConsume(String state, String providerCode) {
        purgeExpired();

        StateRecord record = stateStore.remove(state);
        if (record == null) {
            throw new BadRequestException("Invalid or expired authentication state");
        }

        if (!record.providerCode().equalsIgnoreCase(providerCode)) {
            throw new BadRequestException("Authentication state does not match provider");
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        stateStore.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record StateRecord(String providerCode, Instant expiresAt) {
    }
}

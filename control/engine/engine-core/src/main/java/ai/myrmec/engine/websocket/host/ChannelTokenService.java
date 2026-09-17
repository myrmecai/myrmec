// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mint + consume the short-lived channel tokens offered on {@code session.open}
 * (protocol §7.5, §15 rule 7). The token is a transport binding credential
 * ONLY: single-session, bound to the minting host instance, single-use, and
 * unable to allocate work.
 *
 * <p>Storage: an in-memory registry keyed by token with an expiry sweep —
 * the single-replica dev phase has the same trust boundary as
 * {@code HostConnectionManager}; no changelog table.</p>
 */
@Slf4j
@Component
public class ChannelTokenService {

    @Value("${myrmec.channel.token-ttl-seconds:120}")
    private int tokenTtlSeconds;

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One minted, unconsumed token offer (§7.5: transport binding only). */
    public record ChannelToken(UUID sessionId, UUID hostInstanceId, Instant expiresAt) {}

    private final Map<String, ChannelToken> tokens = new ConcurrentHashMap<>();
    private final AgentHostInstanceRepository instanceRepository;

    public ChannelTokenService(AgentHostInstanceRepository instanceRepository) {
        this.instanceRepository = instanceRepository;
    }

    /** Mint a token bound to (sessionId, instanceId); returns its base64 form. */
    public String mint(UUID sessionId, UUID hostInstanceId) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.put(token, new ChannelToken(sessionId, hostInstanceId,
                Instant.now().plus(Duration.ofSeconds(tokenTtlSeconds))));
        return token;
    }

    /**
     * Result of a consume attempt (§7.5 validation).
     *
     * @param sessionId      the session the token is bound to (null on failure)
     * @param hostInstanceId the minting instance (null on failure) — the
     *                       channel socket adopts it as its connection identity
     * @param error          null on success, else the protocol error code
     */
    public record ConsumeResult(UUID sessionId, UUID hostInstanceId, String error) {
        public boolean ok() { return error == null; }
    }

    /**
     * Consume a token: single-use — a successful call removes it. Rejects
     * unknown, expired, and already-consumed tokens with INVALID_MESSAGE; a
     * valid token presented for the WRONG session, or by a host that does not
     * own the minting instance, with IDENTITY_MISMATCH.
     *
     * @param authenticatedHostId the durable host the HANDSHAKE authenticated
     *                            (HOST_JWT sub) — the token's minting instance
     *                            must belong to it
     */
    public ConsumeResult consume(String token, UUID sessionId, UUID authenticatedHostId) {
        if (token == null || token.isBlank()) {
            return new ConsumeResult(null, null, HostProtocol.INVALID_MESSAGE);
        }
        ChannelToken minted = tokens.remove(token);
        if (minted == null) {
            return new ConsumeResult(null, null, HostProtocol.INVALID_MESSAGE);
        }
        if (Instant.now().isAfter(minted.expiresAt())) {
            log.debug("Channel token rejected: expired (session {})", minted.sessionId());
            return new ConsumeResult(null, null, HostProtocol.INVALID_MESSAGE);
        }
        if (!minted.sessionId().equals(sessionId)) {
            log.warn("Channel token rejected: bound to session {}, presented for {}",
                    minted.sessionId(), sessionId);
            return new ConsumeResult(null, null, HostProtocol.IDENTITY_MISMATCH);
        }
        // §15 rule 7: the token belongs to the minting instance, and the
        // instance must belong to the host the handshake authenticated.
        UUID mintedHostId = instanceRepository.findById(minted.hostInstanceId())
                .map(AgentHostInstance::getAgentHostId)
                .orElse(null);
        if (mintedHostId == null || authenticatedHostId == null
                || !mintedHostId.equals(authenticatedHostId)) {
            log.warn("Channel token rejected: minting instance {} is not owned by "
                            + "authenticated host {}", minted.hostInstanceId(),
                    authenticatedHostId);
            return new ConsumeResult(null, null, HostProtocol.IDENTITY_MISMATCH);
        }
        return new ConsumeResult(minted.sessionId(), minted.hostInstanceId(), null);
    }

    /** Expired-offer sweep: unbonded tokens vanish, the offer window closes. */
    @Scheduled(fixedDelayString = "${myrmec.channel.token-sweep-interval-ms:15000}")
    public void sweepExpired() {
        Iterator<Map.Entry<String, ChannelToken>> it = tokens.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            if (Instant.now().isAfter(it.next().getValue().expiresAt())) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Swept {} expired channel tokens", removed);
        }
    }

    /** Current unconsumed-token count (test/ops visibility). */
    public int size() {
        return tokens.size();
    }
}
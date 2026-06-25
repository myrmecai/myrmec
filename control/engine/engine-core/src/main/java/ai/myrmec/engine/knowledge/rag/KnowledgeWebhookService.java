package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.secret.SecretResolverService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Event-driven (push) re-sync gateway (#25a). Verifies an inbound webhook's
 * HMAC signature against the target source's configured webhook secret, then
 * — rather than syncing inline — stamps {@code sync_requested_at} so
 * {@link KnowledgeSyncScheduler} performs a targeted re-sync on its next tick.
 * This decouples the (untrusted, latency-sensitive) HTTP call from the actual
 * connector work and reuses the existing scheduler + dispatcher machinery.
 *
 * <p><strong>Auth.</strong> The provider signs the raw request body with a
 * shared secret; the engine recomputes {@code HMAC-SHA256(body, secret)} and
 * compares it (constant-time) to the {@code X-Myrmec-Signature} header
 * (optionally {@code sha256=}-prefixed hex). The secret is read from the
 * source's {@code config_json} {@code webhookSecret} field — resolved through
 * {@link SecretResolverService} (project-scoped) when it names a secret, with a
 * literal-value fallback for dev. A source without a configured webhook secret
 * cannot be triggered by push.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeWebhookService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final SecretResolverService secretResolverService;
    private final ObjectMapper objectMapper;

    /** Outcome of a webhook trigger attempt, mapped to an HTTP status by the controller. */
    public enum Outcome {
        /** Signature verified; source marked due for the scheduler. */
        ENQUEUED,
        /** Source has no {@code webhookSecret} configured — push triggering disabled. */
        NOT_CONFIGURED,
        /** Signature missing or did not match. */
        INVALID_SIGNATURE,
        /** Source exists but is disabled; no sync requested. */
        DISABLED
    }

    /**
     * Verify {@code signatureHeader} over {@code rawBody} and, on success, mark
     * the source due for re-sync.
     *
     * @throws ResourceNotFoundException if no source has {@code sourceId}.
     */
    @Transactional
    public Outcome requestResync(UUID sourceId, byte[] rawBody, String signatureHeader) {
        KnowledgeSource source = knowledgeSourceRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeSource", sourceId));

        String secret = resolveWebhookSecret(source);
        if (secret == null || secret.isBlank()) {
            log.warn("Webhook for source {} rejected: no webhookSecret configured", sourceId);
            return Outcome.NOT_CONFIGURED;
        }
        if (!signatureMatches(secret, rawBody, signatureHeader)) {
            log.warn("Webhook for source {} rejected: invalid signature", sourceId);
            return Outcome.INVALID_SIGNATURE;
        }
        if (!source.isEnabled()) {
            log.info("Webhook for source {} accepted but source is disabled — no sync requested", sourceId);
            return Outcome.DISABLED;
        }

        source.setSyncRequestedAt(Instant.now());
        knowledgeSourceRepository.save(source);
        log.info("Webhook accepted for source {} ({}) — marked due for re-sync", sourceId, source.getName());
        return Outcome.ENQUEUED;
    }

    // --- helpers -------------------------------------------------------------

    private String resolveWebhookSecret(KnowledgeSource source) {
        String configured = parseWebhookSecretRef(source.getConfigJson());
        if (configured == null || configured.isBlank()) {
            return null;
        }
        UUID projectId = resolveProjectScope(source.getKnowledgeBaseId());
        // Prefer a managed secret; fall back to treating the value as a literal
        // (mirrors the connectors' inline-token fallback for dev/test).
        return secretResolverService.resolveReferenceString(configured, projectId).orElse(configured);
    }

    private UUID resolveProjectScope(UUID knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return null;
        }
        return knowledgeBaseRepository.findById(knowledgeBaseId)
                .map(KnowledgeBase::getProjectId)
                .orElse(null);
    }

    private String parseWebhookSecretRef(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            WebhookConfig config = objectMapper.readValue(configJson, WebhookConfig.class);
            return config.webhookSecret();
        } catch (Exception e) {
            log.warn("Could not parse source config_json for webhookSecret: {}", e.getMessage());
            return null;
        }
    }

    private boolean signatureMatches(String secret, byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        String provided = stripPrefix(signatureHeader.trim()).toLowerCase(Locale.ROOT);
        String computed = hmacHex(secret, rawBody == null ? new byte[0] : rawBody);
        if (computed == null) {
            return false;
        }
        // Constant-time comparison to avoid leaking match progress via timing.
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripPrefix(String signature) {
        int eq = signature.indexOf('=');
        if (eq > 0 && signature.substring(0, eq).equalsIgnoreCase("sha256")) {
            return signature.substring(eq + 1);
        }
        return signature;
    }

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            log.error("HMAC computation failed: {}", e.getMessage(), e);
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WebhookConfig(String webhookSecret) {
    }
}

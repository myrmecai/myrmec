package ai.myrmec.engine.security.scan;

import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.setting.SystemSettingService;
import ai.myrmec.engine.spi.security.SecretLeakHit;
import ai.myrmec.engine.spi.security.SecretLeakScanner;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 9e — single integration point that wraps the configured
 * {@link SecretLeakScanner} with an enforcement policy
 * (block | redact | warn) and an {@link AuditEventService} hook so
 * platform admins can see hits in the audit trail.
 *
 * <p><b>Mode</b> ({@code myrmec.security.secret-leak.mode}, default
 * {@code REDACT}):
 * <ul>
 *   <li>{@code REDACT} — replace each hit with {@code &lt;redacted:RULE_ID&gt;};
 *       caller continues with the cleaned text.</li>
 *   <li>{@code BLOCK} — return {@link Result#blocked()}; caller MUST
 *       drop the message and surface a generic error to the user.</li>
 *   <li>{@code WARN} — pass-through, hit is only logged + audited.</li>
 * </ul></p>
 *
 * <p>Every non-empty hit triggers an {@code OUTPUT_SECRET_LEAK} audit
 * row carrying the rule ids + source resource (conversation id /
 * message id) so security teams can grep the trail. The redacted /
 * blocked text itself is never recorded — only counts and rule ids —
 * to avoid the audit log becoming a secondary leak surface.</p>
 */
@Slf4j
@Service
public class SecretLeakService {

    /** Enforcement policy. */
    public enum Mode {
        BLOCK,
        REDACT,
        WARN;

        static Mode parse(String s) {
            if (s == null || s.isBlank()) return REDACT;
            try {
                return Mode.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return REDACT;
            }
        }
    }

    /** Well-known system setting key that overrides the env default at runtime. */
    public static final String SETTING_KEY = "secret_leak_mode";

    private final SecretLeakScanner scanner;
    private final AuditEventService auditEventService;
    private final SystemSettingService systemSettingService;
    @Getter
    private final boolean enabled;
    @Getter
    private final Mode defaultMode;

    public SecretLeakService(
            SecretLeakScanner scanner,
            AuditEventService auditEventService,
            SystemSettingService systemSettingService,
            @Value("${myrmec.security.secret-leak.enabled:true}") boolean enabled,
            @Value("${myrmec.security.secret-leak.mode:REDACT}") String mode) {
        this.scanner = scanner;
        this.auditEventService = auditEventService;
        this.systemSettingService = systemSettingService;
        this.enabled = enabled;
        this.defaultMode = Mode.parse(mode);
    }



    /**
     * Scan the assistant turn before it leaves the engine.
     *
     * @param text          the candidate output (assistant message,
     *                      tool result, retrieval payload).
     * @param conversationId conversation id, for the audit row.
     * @param messageId     optional message id (null on streaming
     *                      deltas, populated on complete frames).
     * @return {@link Result} carrying the final text + a flag.
     */
    public Result inspectOutbound(String text, UUID conversationId, UUID messageId) {
        if (!enabled || text == null || text.isEmpty()) {
            return Result.passthrough(text);
        }
        List<SecretLeakHit> hits;
        try {
            hits = scanner.scan(text);
        } catch (Exception ex) {
            // A misbehaving scanner must NEVER take the engine down.
            log.warn("Secret-leak scanner '{}' threw — passing text through: {}",
                    scanner.getId(), ex.getMessage(), ex);
            return Result.passthrough(text);
        }
        if (hits == null || hits.isEmpty()) {
            return Result.passthrough(text);
        }
        // Audit (count by rule id; never log the matched substring itself).
        Map<String, Integer> ruleCounts = new LinkedHashMap<>();
        for (SecretLeakHit hit : hits) {
            ruleCounts.merge(hit.getRuleId(), 1, Integer::sum);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scannerId", scanner.getId());
        payload.put("mode", resolveMode().name());
        payload.put("ruleCounts", ruleCounts);
        try {
            // Resolve actor from security context (null for system actions).
            // OUTPUT_SECRET_LEAK is emitted from the WebSocket worker path where
            // there is typically no authenticated user; actor_id is nullable so
            // we leave it null rather than inventing a UUID that would violate
            // the users(id) foreign key.
            UUID actorId = null;
            String actorName = "SYSTEM";
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof ai.myrmec.engine.user.UserPrincipal up) {
                actorId = up.getUserId();
                actorName = up.getName() != null ? up.getName() : "UNKNOWN";
            }
            auditEventService.recordEvent(
                    ResourceType.CONVERSATION, conversationId,
                    AuditAction.OUTPUT_SECRET_LEAK,
                    "ORGANIZATION", null,
                    actorId,
                    actorName,
                    null, null, null, payload, null);
        } catch (Exception ex) {
            log.warn("Audit of OUTPUT_SECRET_LEAK failed (continuing): {}", ex.getMessage());
        }
        Mode effectiveMode = resolveMode();
        log.warn("Secret leak detected on outbound text (conv={}, msg={}, hits={}, mode={})",
                conversationId, messageId, ruleCounts, effectiveMode);
        return switch (effectiveMode) {
            case BLOCK -> Result.blocked(ruleCounts);
            case REDACT -> Result.redacted(redact(text, hits), ruleCounts);
            case WARN -> Result.warned(text, ruleCounts);
        };
    }

    /** Replace every hit with {@code <redacted:RULE_ID>}. */
    private static String redact(String text, List<SecretLeakHit> hits) {
        // Apply hits right-to-left so earlier offsets stay valid.
        List<SecretLeakHit> ordered = new java.util.ArrayList<>(hits);
        ordered.sort((a, b) -> Integer.compare(b.getStartIndex(), a.getStartIndex()));
        StringBuilder sb = new StringBuilder(text);
        for (SecretLeakHit hit : ordered) {
            int start = Math.max(0, hit.getStartIndex());
            int end = Math.min(sb.length(), hit.getEndIndex());
            if (end <= start) continue;
            String mask = hit.getRedactionMask() != null
                    ? hit.getRedactionMask()
                    : "<redacted:" + hit.getRuleId() + ">";
            sb.replace(start, end, mask);
        }
        return sb.toString();
    }

    /**
     * Outcome of {@link #inspectOutbound(String, UUID, UUID)}.
     * {@link #blocked} is the only state where {@link #text} is null
     * and callers MUST drop the message.
     */
    @lombok.Value
    @lombok.Builder
    public static class Result {
        boolean leakDetected;
        boolean blocked;
        String text;
        Map<String, Integer> ruleCounts;

        public static Result passthrough(String t) {
            return Result.builder().leakDetected(false).blocked(false).text(t).ruleCounts(Map.of()).build();
        }

        public static Result warned(String t, Map<String, Integer> counts) {
            return Result.builder().leakDetected(true).blocked(false).text(t).ruleCounts(counts).build();
        }

        public static Result redacted(String t, Map<String, Integer> counts) {
            return Result.builder().leakDetected(true).blocked(false).text(t).ruleCounts(counts).build();
        }

        public static Result blocked(Map<String, Integer> counts) {
            return Result.builder().leakDetected(true).blocked(true).text(null).ruleCounts(counts).build();
        }
    }

    /**
     * Test-only constructor for unit tests that don't need the audit
     * trail (in-process verification of mode + redaction).
     */
    static SecretLeakService forTest(SecretLeakScanner scanner, AuditEventService audit, Mode mode) {
        return new SecretLeakService(scanner, audit, null, true, mode.name());
    }

    /** Resolve the effective mode for a test instance that has no SystemSettingService. */
    private Mode resolveMode() {
        if (systemSettingService == null) {
            return defaultMode;
        }
        String configured = systemSettingService.getString(SETTING_KEY, "");
        return configured.isBlank() ? defaultMode : Mode.parse(configured);
    }
}

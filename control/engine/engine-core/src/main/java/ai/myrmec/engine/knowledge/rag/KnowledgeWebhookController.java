package ai.myrmec.engine.knowledge.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Inbound webhook endpoint for event-driven (push) re-sync (#25a).
 *
 * <p>External systems (Git hosts, S3 event notifications, SaaS apps) call
 * {@code POST /api/v1/knowledge/webhooks/{sourceId}} when their content changes.
 * The request is authenticated by an HMAC-SHA256 signature over the raw body
 * (not a JWT), so this path is {@code permitAll} in {@link
 * ai.myrmec.engine._system.security.SecurityConfig} and authorisation is
 * enforced entirely by {@link KnowledgeWebhookService}. On a verified call the
 * source is marked due and {@link KnowledgeSyncScheduler} performs the actual
 * sync on its next tick (HTTP returns promptly).</p>
 */
@RestController
@RequestMapping("/api/v1/knowledge/webhooks")
@RequiredArgsConstructor
@Slf4j
public class KnowledgeWebhookController {

    /** Provider-supplied signature header: {@code sha256=<hex>} (prefix optional). */
    public static final String SIGNATURE_HEADER = "X-Myrmec-Signature";

    private final KnowledgeWebhookService webhookService;

    @PostMapping("/{sourceId}")
    public ResponseEntity<Map<String, String>> trigger(
            @PathVariable UUID sourceId,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody(required = false) byte[] body) {

        KnowledgeWebhookService.Outcome outcome =
                webhookService.requestResync(sourceId, body, signature);

        return switch (outcome) {
            case ENQUEUED -> ResponseEntity.accepted()
                    .body(Map.of("status", "ENQUEUED"));
            case DISABLED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("errorCode", "RESOURCE_DISABLED",
                            "message", "The knowledge source is disabled."));
            case NOT_CONFIGURED, INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("errorCode", "UNAUTHORIZED",
                            "message", "Webhook signature verification failed."));
        };
    }
}

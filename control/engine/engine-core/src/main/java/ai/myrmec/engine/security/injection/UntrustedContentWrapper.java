package ai.myrmec.engine.security.injection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 9f — wraps untrusted text (retrieval hits, tool outputs, web
 * fetches) in {@code <untrusted source="…">…</untrusted>} envelopes
 * before it lands in the prompt that goes to the model.
 *
 * <p>Rationale: a model can be told upfront that content inside
 * {@code <untrusted>} blocks is data, not instructions. This is the
 * cheapest and most-portable prompt-injection defence —
 * Anthropic, OpenAI, and Llama all respond to this convention.</p>
 *
 * <p>The wrapper also escapes any embedded {@code <untrusted>} /
 * {@code </untrusted>} tags inside the payload itself, otherwise an
 * attacker could close the envelope early and inject instructions in
 * what looks to the model like the trusted system frame.</p>
 *
 * <p>{@code myrmec.security.untrusted-wrapper.enabled} (default
 * {@code true}) toggles the entire feature for compatibility — when
 * disabled the methods return their inputs unchanged.</p>
 */
@Component
public class UntrustedContentWrapper {

    private final boolean enabled;

    public UntrustedContentWrapper(
            @Value("${myrmec.security.untrusted-wrapper.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Wrap retrieval-style content (passage from a knowledge base).
     *
     * @param passage    the original passage text (may be null/empty —
     *                   wrapper still produces a self-closing envelope
     *                   so callers don't need to special-case).
     * @param sourceName human-readable name of the source (sanitised
     *                   into the {@code source} attribute).
     * @return wrapped string, or the original input if disabled.
     */
    public String wrapRetrieval(String passage, String sourceName) {
        if (!enabled) {
            return passage;
        }
        String safeBody = sanitiseBody(passage);
        String safeSource = sanitiseAttribute(sourceName == null ? "" : sourceName);
        return "<untrusted source=\"" + safeSource + "\" type=\"retrieval\">"
                + safeBody
                + "</untrusted>";
    }

    /**
     * Wrap tool-output text. Use this for the JSON / text emitted by
     * a tool invocation that the agent is about to feed back to the
     * model.
     */
    public String wrapToolOutput(String output, String toolCode) {
        if (!enabled) {
            return output;
        }
        String safeBody = sanitiseBody(output);
        String safeSource = sanitiseAttribute(toolCode == null ? "" : toolCode);
        return "<untrusted source=\"" + safeSource + "\" type=\"tool\">"
                + safeBody
                + "</untrusted>";
    }

    /**
     * Strip any embedded envelope tags from the body so an attacker
     * cannot close the wrapper early. Replaces {@code <untrusted}
     * (case-insensitive) and {@code </untrusted>} with a benign
     * marker. We deliberately keep the rest of the body verbatim —
     * over-zealous escaping breaks legitimate code-block content.
     */
    static String sanitiseBody(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        return body
                .replaceAll("(?i)</?untrusted\\b", "&lt;UNTRUSTED-TAG&gt;");
    }

    /**
     * XML attribute sanitisation — the source name is short and is
     * rendered into a literal {@code source="…"} pair, so we strip
     * quotes and angle brackets only.
     */
    static String sanitiseAttribute(String value) {
        return value
                .replace("\"", "'")
                .replace("<", "")
                .replace(">", "")
                .replace("\n", " ")
                .trim();
    }

    /** Exposed for tests + audit. */
    public boolean isEnabled() {
        return enabled;
    }
}

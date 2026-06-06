package ai.myrmec.engine.security.scan;

import ai.myrmec.engine.spi.security.SecretLeakHit;
import ai.myrmec.engine.spi.security.SecretLeakScanner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 9e — Community {@link SecretLeakScanner} implementation. Pure
 * regex on a curated rule set; no network calls. Intended as the V1
 * default — Enterprise tiers can override via SPI for entropy / ML
 * scanners.
 *
 * <p>Rules are intentionally conservative — false positives are far
 * worse than false negatives here because the engine may BLOCK on a
 * hit. The set is biased toward formats that have a fixed-shape
 * envelope (AWS keys, JWTs, GitHub tokens) rather than free-form
 * strings.</p>
 */
@Component
public class RegexSecretLeakScanner implements SecretLeakScanner {

    /** One regex rule with a stable id. */
    private record Rule(String id, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            // AWS access key id (AKIA…20-char) — anchored on word boundary.
            new Rule("AWS_ACCESS_KEY_ID",
                    Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b")),
            // GitHub fine-grained / classic / OAuth token prefixes.
            new Rule("GITHUB_TOKEN",
                    Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{20,251}\\b")),
            // OpenAI org+key pattern.
            new Rule("OPENAI_API_KEY",
                    Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,200}\\b")),
            // Generic JWT (three base64url segments separated by dots).
            new Rule("JWT_TOKEN",
                    Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")),
            // Slack bot / user tokens.
            new Rule("SLACK_TOKEN",
                    Pattern.compile("\\bxox[abp]-[A-Za-z0-9-]{10,}\\b"))
    );

    @Override
    public List<SecretLeakHit> scan(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<SecretLeakHit> hits = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                hits.add(SecretLeakHit.builder()
                        .ruleId(rule.id())
                        .startIndex(m.start())
                        .endIndex(m.end())
                        .build());
            }
        }
        return hits;
    }

    @Override
    public String getId() {
        return "regex-community";
    }
}

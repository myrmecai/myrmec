package ai.myrmec.engine._system.exception;

/**
 * Utility class for masking sensitive data in logs.
 */
public final class SensitiveDataMasker {

    private SensitiveDataMasker() {
        // Utility class
    }

    /**
     * Mask the middle part of a string, keeping prefix and suffix visible.
     * Example: "myr_agent_abc123xyz789" -> "myr_agent_****xyz789"
     * 
     * @param value the value to mask
     * @param visiblePrefix number of characters to keep visible at start
     * @param visibleSuffix number of characters to keep visible at end
     * @return masked string
     */
    public static String mask(String value, int visiblePrefix, int visibleSuffix) {
        if (value == null) {
            return "[null]";
        }
        
        int length = value.length();
        int totalVisible = visiblePrefix + visibleSuffix;
        
        if (length <= totalVisible) {
            // String too short to mask meaningfully
            return "****";
        }
        
        String prefix = value.substring(0, visiblePrefix);
        String suffix = value.substring(length - visibleSuffix);
        int maskedLength = length - totalVisible;
        
        return prefix + "*".repeat(Math.min(maskedLength, 12)) + suffix;
    }

    /**
     * Mask a registration key, keeping the prefix visible.
     * Example: "myr_agent_abc123xyz789abc" -> "myr_agent_****abc"
     */
    public static String maskRegistrationKey(String key) {
        if (key == null) {
            return "[null]";
        }
        // Keep "myr_agent_" prefix (10 chars) and last 6 chars visible
        return mask(key, 10, 6);
    }

    /**
     * Mask an email address.
     * Example: "john.doe@example.com" -> "j****e@example.com"
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "[invalid]";
        }
        
        int atIndex = email.indexOf('@');
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        
        if (local.length() <= 2) {
            return "****" + domain;
        }
        
        return local.charAt(0) + "****" + local.charAt(local.length() - 1) + domain;
    }

    /**
     * Mask a JWT token, keeping only type indicator visible.
     * Example: "eyJhbGciOiJI..." -> "eyJ****..."
     */
    public static String maskToken(String token) {
        if (token == null || token.length() < 10) {
            return "[invalid]";
        }
        return token.substring(0, 3) + "****..." ;
    }
}

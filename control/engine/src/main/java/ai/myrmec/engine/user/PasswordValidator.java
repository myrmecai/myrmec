package ai.myrmec.engine.user;

import ai.myrmec.engine._system.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Password policy validator.
 * 
 * <p>Requirements:
 * <ul>
 *   <li>Length: 8-50 characters</li>
 *   <li>At least 1 letter (a-z, A-Z)</li>
 *   <li>At least 1 digit (0-9)</li>
 *   <li>At least 1 special character</li>
 * </ul>
 */
@Component
public class PasswordValidator {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 50;
    private static final Pattern LETTER_PATTERN = Pattern.compile("[a-zA-Z]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL_PATTERN = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?/~`'\"\\\\]");

    /**
     * Validate password against policy.
     * 
     * @throws BadRequestException if password does not meet requirements
     */
    public void validate(String password) {
        List<String> violations = new ArrayList<>();

        if (password == null || password.isEmpty()) {
            throw BadRequestException.forField("password", "REQUIRED", "Password is required");
        }

        if (password.length() < MIN_LENGTH) {
            violations.add("Password must be at least " + MIN_LENGTH + " characters");
        }

        if (password.length() > MAX_LENGTH) {
            violations.add("Password must not exceed " + MAX_LENGTH + " characters");
        }

        if (!LETTER_PATTERN.matcher(password).find()) {
            violations.add("Password must contain at least one letter");
        }

        if (!DIGIT_PATTERN.matcher(password).find()) {
            violations.add("Password must contain at least one digit");
        }

        if (!SPECIAL_PATTERN.matcher(password).find()) {
            violations.add("Password must contain at least one special character");
        }

        if (!violations.isEmpty()) {
            throw BadRequestException.forField("password", "INVALID_PASSWORD", String.join("; ", violations));
        }
    }
}

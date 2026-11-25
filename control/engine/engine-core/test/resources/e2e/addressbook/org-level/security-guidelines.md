# Security Guidelines

## Authentication & Authorization

### Password Requirements
- Minimum 8 characters
- Must contain: uppercase, lowercase, digit, special character
- Hash using BCrypt with work factor 10+
- Never store plaintext passwords

### Token Security
- JWT tokens for stateless auth
- Access tokens: 15 minute expiry
- Refresh tokens: 7 day expiry, stored securely
- Rotate secrets periodically

### Authorization
- Check permissions at API layer
- Use role-based access control (RBAC)
- Principle of least privilege

## Input Validation

### All User Input Must Be Validated

```java
// Always validate
public Contact createContact(ContactRequest request) {
    Objects.requireNonNull(request.email(), "Email is required");
    if (!EMAIL_PATTERN.matcher(request.email()).matches()) {
        throw new ValidationException("Invalid email format");
    }
    // ... process
}
```

### Validation Rules
- Validate type, format, length, and range
- Sanitize before display (XSS prevention)
- Use parameterized queries (SQL injection prevention)
- Validate file uploads (type, size, content)

## Data Protection

### Sensitive Data
- Encrypt PII at rest (AES-256)
- Use TLS 1.3 for data in transit
- Mask sensitive data in logs
- Implement data retention policies

### API Security
- Rate limiting on all endpoints
- CORS configuration (allow only trusted origins)
- Security headers (CSP, X-Frame-Options, etc.)

## Secrets Management

- Never commit secrets to version control
- Use environment variables or secret managers
- Rotate credentials regularly
- Separate secrets per environment

```yaml
# Good - use environment variables
database:
  password: ${DB_PASSWORD}

# Bad - hardcoded
database:
  password: mysecretpassword
```

## Audit Logging

Log security-relevant events:
- Authentication attempts (success/failure)
- Authorization failures
- Data access (who accessed what)
- Configuration changes

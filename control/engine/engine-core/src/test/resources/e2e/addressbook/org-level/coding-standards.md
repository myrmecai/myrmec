# Company Coding Standards

## General Principles

1. **Code Readability**: Write code for humans first, computers second
2. **DRY Principle**: Don't Repeat Yourself - extract common logic
3. **Single Responsibility**: Each class/function should do one thing well
4. **Meaningful Names**: Use descriptive names for variables, functions, and classes

## Naming Conventions

### Variables and Functions
- Use camelCase for variables and function names
- Use descriptive names that explain purpose: `userEmail` not `e`
- Boolean variables should be prefixed: `isActive`, `hasPermission`, `canEdit`

### Classes and Types
- Use PascalCase for class names
- Use nouns for entity classes: `Contact`, `User`, `Order`
- Use verbs for service classes: `ContactService`, `EmailSender`

## Error Handling

1. **Never swallow exceptions** - always log or rethrow
2. **Use specific exception types** - not generic Exception
3. **Include context in error messages** - what failed and why
4. **Fail fast** - validate inputs early

```java
// Good
if (email == null || email.isBlank()) {
    throw new IllegalArgumentException("Email is required");
}

// Bad
try {
    process(email);
} catch (Exception e) {
    // silently ignored
}
```

## Logging

1. Use appropriate log levels:
   - `ERROR`: Unexpected failures requiring attention
   - `WARN`: Recoverable issues, degraded functionality
   - `INFO`: Significant business events
   - `DEBUG`: Detailed technical information

2. Include relevant context:
```java
log.info("Contact created: id={}, email={}", contact.getId(), contact.getEmail());
```

3. Never log sensitive data (passwords, tokens, PII)

## Code Organization

- Keep files under 300 lines
- Keep functions under 30 lines
- Maximum 3 levels of nesting
- Group related code together

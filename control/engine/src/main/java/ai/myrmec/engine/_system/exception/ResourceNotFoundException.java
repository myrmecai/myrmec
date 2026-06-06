package ai.myrmec.engine._system.exception;

import lombok.Getter;

/**
 * Exception for when a requested resource is not found.
 * Includes resource type and identifier for debugging.
 */
@Getter
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;
    private final String identifier;

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(String.format("%s not found: %s", resourceType, identifier));
        this.resourceType = resourceType;
        this.identifier = identifier;
    }

    public ResourceNotFoundException(String resourceType, Object identifier) {
        this(resourceType, String.valueOf(identifier));
    }

    /**
     * Generic factory method.
     */
    public static ResourceNotFoundException of(String resourceType, Object identifier) {
        return new ResourceNotFoundException(resourceType, identifier);
    }

    /**
     * Factory method with field name for lookup by non-ID field.
     */
    public static ResourceNotFoundException of(String resourceType, String field, Object value) {
        return new ResourceNotFoundException(resourceType, field + "=" + value);
    }

    /**
     * Factory method for agent not found.
     */
    public static ResourceNotFoundException agent(Object id) {
        return new ResourceNotFoundException("Agent", id);
    }

    /**
     * Factory method for registration key not found.
     */
    public static ResourceNotFoundException registrationKey(Object id) {
        return new ResourceNotFoundException("RegistrationKey", id);
    }

    /**
     * Factory method for project not found.
     */
    public static ResourceNotFoundException project(Object id) {
        return new ResourceNotFoundException("Project", id);
    }

    /**
     * Factory method for user not found.
     */
    public static ResourceNotFoundException user(Object id) {
        return new ResourceNotFoundException("User", id);
    }

    /**
     * Factory method for agent instance not found.
     */
    public static ResourceNotFoundException agentInstance(Object id) {
        return new ResourceNotFoundException("AgentInstance", id);
    }

    /**
     * Factory method for agent profile not found.
     */
    public static ResourceNotFoundException agentProfile(Object id) {
        return new ResourceNotFoundException("AgentProfile", id);
    }
}

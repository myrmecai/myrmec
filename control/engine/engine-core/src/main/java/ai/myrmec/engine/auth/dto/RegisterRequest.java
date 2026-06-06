package ai.myrmec.engine.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Request to register an agent instance.
 * The registration key must be associated with an existing Agent definition.
 */
@Data
public class RegisterRequest {

    /**
     * Registration key - set from X-Registration-Key header, not from body.
     */
    private String registrationKey;

    /**
     * Hostname of the machine where the agent instance is running.
     */
    @Size(max = 255, message = "Hostname must be at most 255 characters")
    private String hostname;

    /**
     * IP address of the agent instance.
     */
    @Size(max = 45, message = "IP address must be at most 45 characters")
    private String ipAddress;

    /**
     * SDK/runtime version of the agent instance.
     */
    @Size(max = 20, message = "Runtime version must be at most 20 characters")
    private String runtimeVersion;

    /**
     * Additional metadata about the agent instance.
     */
    private Map<String, Object> metadata;
}

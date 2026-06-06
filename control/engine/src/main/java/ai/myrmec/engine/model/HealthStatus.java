package ai.myrmec.engine.model;

/**
 * Model health status for on-premise deployments.
 */
public enum HealthStatus {
    /**
     * Model is responding normally
     */
    HEALTHY,
    
    /**
     * Model is slow or experiencing issues
     */
    DEGRADED,
    
    /**
     * Model is not responding
     */
    UNHEALTHY,
    
    /**
     * Health check not configured or not yet run
     */
    UNKNOWN,
    
    /**
     * Model is loading into memory
     */
    LOADING
}

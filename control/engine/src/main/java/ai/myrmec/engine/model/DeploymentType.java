package ai.myrmec.engine.model;

/**
 * Model deployment type.
 */
public enum DeploymentType {
    /**
     * Hosted by cloud provider (OpenAI, Anthropic, Azure) - requires API key
     */
    CLOUD,
    
    /**
     * Self-hosted on internal infrastructure - API key optional
     */
    ON_PREMISE
}

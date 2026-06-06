package ai.myrmec.engine.knowledge.dto;

/**
 * Request DTO for resolving knowledge context.
 */
public record ResolveContextRequest(
        /**
         * File path to filter knowledge documents by appliesTo patterns.
         * If null, returns all documents without filtering.
         */
        String filePath,
        
        /**
         * Optional character budget for embedded knowledge.
         * Defaults to 32000 if not specified.
         */
        Integer charBudget
) {}

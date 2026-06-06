package ai.myrmec.engine.spi.retrieval;

/**
 * Checked exception thrown by {@link RetrievalProvider#query} on any non-empty
 * failure. The engine catches and demotes to a per-task warning rather than
 * failing the task so agents can degrade gracefully when the KB is offline.
 */
public class RetrievalException extends Exception {

    public RetrievalException(String message) {
        super(message);
    }

    public RetrievalException(String message, Throwable cause) {
        super(message, cause);
    }
}

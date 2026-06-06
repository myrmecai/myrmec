package ai.myrmec.engine.spi.connector;

/**
 * Checked exception thrown by {@link KnowledgeSourceConnector#sync} on
 * non-recoverable failures (auth rejected, source unreachable,
 * configuration invalid). Per-resource failures should be reported through
 * {@link SyncResult#errors()} instead.
 */
public class ConnectorException extends Exception {

    public ConnectorException(String message) {
        super(message);
    }

    public ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}

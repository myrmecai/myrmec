package ai.myrmec.engine.spi.storage;

/**
 * Thrown by {@link BlobStore#get} when no blob exists at the requested key.
 * A runtime exception so call sites that have already validated the metadata
 * row need not declare it; a missing blob behind a present row is an internal
 * consistency error, not a client error.
 */
public class BlobNotFoundException extends RuntimeException {

    public BlobNotFoundException(String key) {
        super("No blob stored under key: " + key);
    }
}

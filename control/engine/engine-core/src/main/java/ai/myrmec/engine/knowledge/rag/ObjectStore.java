package ai.myrmec.engine.knowledge.rag;

import java.io.IOException;
import java.util.List;

/**
 * Minimal seam over an S3-protocol object store, used by {@link S3Connector}
 * (#22). Keeping the connector's storage access behind this interface lets the
 * crawl/extract/chunk logic be unit-tested with an in-memory fake — no Docker,
 * LocalStack, or live cloud account required.
 *
 * <p>Implementations are created per-sync by an {@link ObjectStoreFactory} from
 * the source's endpoint + resolved credentials, and closed when the sync ends.</p>
 */
interface ObjectStore extends AutoCloseable {

    /** A single stored object: its key and size in bytes. */
    record Entry(String key, long size) {
    }

    /**
     * List every object under {@code prefix} in {@code bucket} (recursively),
     * excluding pseudo-directory placeholders.
     *
     * @throws IOException when the bucket is unreachable or access is denied;
     *         the connector treats this as a non-recoverable failure.
     */
    List<Entry> list(String bucket, String prefix) throws IOException;

    /**
     * Read the full bytes of one object.
     *
     * @throws IOException when the object cannot be read; the connector records
     *         this per-object and continues (PARTIAL sync).
     */
    byte[] read(String bucket, String key) throws IOException;

    @Override
    void close() throws IOException;
}

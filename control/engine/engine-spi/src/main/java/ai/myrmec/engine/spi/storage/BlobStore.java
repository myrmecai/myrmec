package ai.myrmec.engine.spi.storage;

/**
 * Pluggable binary blob store for attachment payloads (#103).
 *
 * <p>The engine never inlines large attachment bytes in the database — the
 * {@code conversation_message_attachments} row carries only metadata plus a
 * storage {@code key}, and the bytes live in whatever {@code BlobStore} is
 * wired. {@code engine-core} ships a local-filesystem implementation;
 * enterprise / cloud deployments can supply an S3 / Azure-Blob / GCS
 * implementation by contributing a {@code @Component} that implements this
 * interface (the bundled one is {@code @ConditionalOnMissingBean}).</p>
 *
 * <p>Keys are opaque, caller-generated strings (the attachment service uses
 * {@code <conversationId>/<attachmentId>}); implementations MUST treat them
 * as a flat namespace and MUST NOT interpret them as filesystem paths beyond
 * their own rooting. The contract is content-addressable only in so far as
 * {@link StoredBlob#sha256()} is returned for integrity verification.</p>
 */
public interface BlobStore {

    /**
     * Persist {@code content} under {@code key}, overwriting any existing
     * blob at that key. Returns the stored size and content hash.
     *
     * @param key       opaque storage key (non-null, non-blank)
     * @param content   the bytes to store (non-null)
     * @param mediaType the declared MIME type (informational; may be null)
     * @return a {@link StoredBlob} descriptor
     */
    StoredBlob put(String key, byte[] content, String mediaType);

    /**
     * Read the bytes previously stored under {@code key}.
     *
     * @param key the storage key
     * @return the stored bytes
     * @throws BlobNotFoundException if no blob exists at {@code key}
     */
    byte[] get(String key);

    /**
     * Delete the blob at {@code key}. A no-op if the key does not exist
     * (delete is idempotent).
     *
     * @param key the storage key
     */
    void delete(String key);

    /** Stable identifier for the implementation (for logs + audit). */
    String getId();
}

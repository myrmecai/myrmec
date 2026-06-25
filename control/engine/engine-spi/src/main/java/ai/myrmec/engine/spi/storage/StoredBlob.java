package ai.myrmec.engine.spi.storage;

/**
 * Descriptor returned by {@link BlobStore#put} — the persisted size and a
 * hex-encoded SHA-256 of the stored bytes, for integrity verification.
 *
 * @param key       the storage key the blob was written under
 * @param sizeBytes the number of bytes stored
 * @param sha256    lowercase hex SHA-256 of the stored bytes
 */
public record StoredBlob(String key, long sizeBytes, String sha256) {
}

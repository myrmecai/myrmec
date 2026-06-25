package ai.myrmec.engine.attachment.storage;

import ai.myrmec.engine.spi.storage.BlobNotFoundException;
import ai.myrmec.engine.spi.storage.BlobStore;
import ai.myrmec.engine.spi.storage.StoredBlob;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Bundled {@link BlobStore} that writes attachment bytes under a local
 * directory ({@code myrmec.attachments.storage-dir}, default
 * {@code ./data/attachments}). Suitable for single-node and dev deployments;
 * cloud deployments supply an object-store implementation that takes
 * precedence (registered as the default via {@code @ConditionalOnMissingBean}
 * in {@code AttachmentInfrastructureConfig}).
 *
 * <p>Keys are sanitised to a flat {@code <segment>/<segment>} shape and
 * resolved <em>strictly</em> beneath the root — any key that would escape the
 * root (via {@code ..} or an absolute path) is rejected, closing the path
 * traversal hole (OWASP A01/A03).</p>
 */
@Slf4j
public class LocalFilesystemBlobStore implements BlobStore {

    private final Path root;

    public LocalFilesystemBlobStore(String storageDir) {
        this.root = Paths.get(storageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create attachment storage dir " + root, e);
        }
        log.info("Local attachment blob store rooted at {}", root);
    }

    @Override
    public StoredBlob put(String key, byte[] content, String mediaType) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write blob " + key, e);
        }
        return new StoredBlob(key, content.length, sha256(content));
    }

    @Override
    public byte[] get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw new BlobNotFoundException(key);
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read blob " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete blob " + key, e);
        }
    }

    @Override
    public String getId() {
        return "local-fs";
    }

    /** Resolve {@code key} strictly beneath {@link #root}, rejecting escapes. */
    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("blob key must not be blank");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("blob key escapes storage root: " + key);
        }
        return resolved;
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package ai.myrmec.engine.attachment;

import ai.myrmec.engine.attachment.scan.BundledContentScanProvider;
import ai.myrmec.engine.attachment.storage.LocalFilesystemBlobStore;
import ai.myrmec.engine.spi.scan.ContentScanProvider;
import ai.myrmec.engine.spi.storage.BlobStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the bundled attachment infrastructure defaults (#103/#104).
 *
 * <p>Both beans use {@link ConditionalOnMissingBean} so an enterprise or
 * cloud deployment can supply its own {@link BlobStore} (object store) or
 * {@link ContentScanProvider} (ClamAV / vendor API) that takes precedence.
 * Declaring them as {@code @Bean} methods here — rather than annotating the
 * implementation classes with {@code @Component @ConditionalOnMissingBean} —
 * is the reliable Spring Boot pattern: condition evaluation on scanned
 * {@code @Component} classes is order-dependent and can silently drop the
 * default bean.</p>
 */
@Configuration
public class AttachmentInfrastructureConfig {

    @Bean
    @ConditionalOnMissingBean(BlobStore.class)
    public BlobStore localFilesystemBlobStore(
            @Value("${myrmec.attachments.storage-dir:./data/attachments}") String storageDir) {
        return new LocalFilesystemBlobStore(storageDir);
    }

    @Bean
    @ConditionalOnMissingBean(ContentScanProvider.class)
    public ContentScanProvider bundledContentScanProvider() {
        return new BundledContentScanProvider();
    }
}

package ai.myrmec.engine._system.crypto;

import ai.myrmec.engine.spi.crypto.EncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the Community {@link EncryptionService} implementation under
 * {@link ConditionalOnMissingBean} so an Enterprise jar can substitute an
 * HSM/KMS-backed variant simply by publishing its own {@code EncryptionService}
 * bean ahead of this auto-configuration.
 */
@AutoConfiguration
public class CryptoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EncryptionService.class)
    public EncryptionService basicEncryptionService(
            @Value("${myrmec.encryption.key:default-encryption-key-change-me}") String key,
            @Value("${myrmec.encryption.previous-keys:}") String previousKeysCsv
    ) {
        return new BasicEncryptionService(key, previousKeysCsv);
    }
}

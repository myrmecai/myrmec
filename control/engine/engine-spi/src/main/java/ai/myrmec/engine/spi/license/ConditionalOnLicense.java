package ai.myrmec.engine.spi.license;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation that registers an annotated {@code @Bean} or {@code @Configuration}
 * only when the running {@link LicenseService} reports the given {@link LicenseFeature}
 * as licensed.
 *
 * <p>Used by every Enterprise auto-configuration so that the licensing decision is
 * resolved by Spring at context refresh and again whenever the application is
 * restarted with a different license set. Runtime license expiry does NOT cause the
 * already-registered bean to disappear (Spring contexts are static); the bean itself
 * must consult {@link LicenseService#isLicensedFor(LicenseFeature)} on every public
 * method entry and forward to a held Community delegate when the feature is no longer
 * licensed.
 *
 * <p>Evaluation runs in the {@code REGISTER_BEAN} configuration phase via
 * {@code LicenseCondition}, so {@code LicenseService} is guaranteed to be available
 * when the condition is checked.
 *
 * <p>Example:
 * <pre>
 * &#64;Bean
 * &#64;ConditionalOnLicense(LicenseFeature.FORENSIC_AUDIT)
 * AuditWriter forensicAuditWriter(...) { ... }
 * </pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(LicenseCondition.class)
public @interface ConditionalOnLicense {

    /** The feature whose licensed-ness gates this bean. */
    LicenseFeature value();
}

package ai.myrmec.engine.spi.license;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * Spring {@link Condition} backing the {@link ConditionalOnLicense} annotation.
 *
 * <p>Runs in the {@link ConfigurationPhase#REGISTER_BEAN REGISTER_BEAN} phase so the
 * {@link LicenseService} bean (registered by either the Community auto-config or the
 * Enterprise auto-config running before it) is already in the BeanFactory when this
 * condition is evaluated. If no {@code LicenseService} is present the condition fails
 * closed: the gated bean is not registered.
 */
public class LicenseCondition implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnLicense.class.getName());
        if (attrs == null) {
            return false;
        }
        Object featureAttr = attrs.get("value");
        if (!(featureAttr instanceof LicenseFeature feature)) {
            return false;
        }
        try {
            LicenseService licenseService = context.getBeanFactory().getBean(LicenseService.class);
            return licenseService.isLicensedFor(feature);
        } catch (NoSuchBeanDefinitionException e) {
            return false;
        }
    }
}

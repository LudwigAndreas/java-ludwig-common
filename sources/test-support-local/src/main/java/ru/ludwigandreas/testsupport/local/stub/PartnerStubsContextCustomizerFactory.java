package ru.ludwigandreas.testsupport.local.stub;

import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContextAnnotationUtils;

/**
 * Turns {@link WithPartnerStubs} into started stubs for the test's application context.
 *
 * <p>Registered in {@code META-INF/spring.factories}, which is the only thing this module registers
 * anywhere. It returns nothing for a test class that does not carry the annotation, so having the
 * module on a test classpath starts no server and overrides no property.
 */
public final class PartnerStubsContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        WithPartnerStubs annotation =
                TestContextAnnotationUtils.findMergedAnnotation(testClass, WithPartnerStubs.class);
        return annotation == null ? null : new PartnerStubsContextCustomizer(annotation.value());
    }

    /**
     * The customizer for one catalog.
     *
     * <p>Equality is the catalog class, because the customizer is part of the context cache key: two
     * test classes naming the same catalog must share a context, and two naming different catalogs
     * must not.
     */
    private record PartnerStubsContextCustomizer(Class<? extends StubCatalog> catalogType)
            implements ContextCustomizer {

        @Override
        public void customizeContext(
                ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            new StubServersInitializer(BeanUtils.instantiateClass(catalogType)).initialize(context);
        }
    }
}

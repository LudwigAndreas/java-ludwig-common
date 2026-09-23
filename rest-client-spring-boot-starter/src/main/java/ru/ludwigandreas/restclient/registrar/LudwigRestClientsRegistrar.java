package ru.ludwigandreas.restclient.registrar;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import ru.ludwigandreas.restclient.annotation.EnableLudwigRestClients;
import ru.ludwigandreas.restclient.annotation.LudwigRestClient;
import ru.ludwigandreas.restclient.config.RestClientProperties;

/**
 * Finds {@link LudwigRestClient} interfaces and registers a proxy bean for each.
 *
 * <h2>Where it looks</h2>
 *
 * <p>With {@link EnableLudwigRestClients} present, exactly the packages it names. Without it - the
 * common case - the application's auto-configuration packages, i.e. the package of
 * {@code @SpringBootApplication} and below, which is where a service keeps its own client interfaces.
 * That is why the annotation is optional: the default is right nearly always.
 *
 * <p>The two are alternatives rather than additive. Unioning them would make it impossible to
 * <em>narrow</em> the scan, and a service that has deliberately moved its interfaces out of the
 * application package would still get the application package scanned.
 *
 * <h2>Why a registrar and not a component scan</h2>
 *
 * <p>These are interfaces. A component scan registers bean definitions for concrete classes; an
 * interface has no constructor to call. Registering a {@link RestClientInterfaceFactoryBean} per
 * interface is what turns "an interface with annotations on it" into "a bean you can inject", and it
 * is how Spring Data and Spring Cloud OpenFeign solve the identical problem.
 */
public class LudwigRestClientsRegistrar
        implements ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware {

    private static final Logger log = LoggerFactory.getLogger(LudwigRestClientsRegistrar.class);

    private Environment environment;
    private ResourceLoader resourceLoader;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        if (!environment.getProperty(RestClientProperties.PREFIX + ".enabled", Boolean.class, true)) {
            // The master switch has to be honoured here as well as in the auto-configurations: a
            // proxy registered against a registry that does not exist fails at injection time with a
            // message about a missing bean, which tells nobody that the feature was switched off.
            return;
        }
        Set<String> packages = basePackages(metadata, registry);
        if (packages.isEmpty()) {
            return;
        }
        ClassPathScanningCandidateComponentProvider scanner = scanner();
        int registered = 0;
        for (String basePackage : packages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                register(registry, candidate);
                registered++;
            }
        }
        log.debug("Registered {} @LudwigRestClient interfaces from {}", registered, packages);
    }

    /**
     * A scanner that accepts interfaces.
     *
     * <p>{@code isCandidateComponent} is overridden because Spring's default rejects anything that is
     * not a concrete, instantiable class - which is every type this registrar is looking for. The
     * {@code isIndependent} check is kept: an inner interface of another type cannot be proxied
     * without its enclosing instance.
     */
    private ClassPathScanningCandidateComponentProvider scanner() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false, environment) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                        return definition.getMetadata().isInterface()
                                && definition.getMetadata().isIndependent();
                    }
                };
        scanner.setResourceLoader(resourceLoader);
        scanner.addIncludeFilter(new AnnotationTypeFilter(LudwigRestClient.class));
        return scanner;
    }

    private void register(BeanDefinitionRegistry registry, BeanDefinition candidate) {
        String className = candidate.getBeanClassName();
        Class<?> interfaceType = resolve(className);
        LudwigRestClient annotation = interfaceType.getAnnotation(LudwigRestClient.class);
        String clientName = clientName(interfaceType, annotation);

        AbstractBeanDefinition definition = BeanDefinitionBuilder
                .genericBeanDefinition(RestClientInterfaceFactoryBean.class)
                .addPropertyValue("interfaceType", interfaceType)
                .addPropertyValue("clientName", clientName)
                .getBeanDefinition();
        // The FactoryBean's product type, so injection by the interface type resolves without the
        // context having to instantiate the factory first.
        definition.setAttribute("factoryBeanObjectType", interfaceType);
        definition.setPrimary(true);

        String beanName = StringUtils.hasText(annotation.beanName())
                ? annotation.beanName()
                : StringUtils.uncapitalize(interfaceType.getSimpleName());
        registry.registerBeanDefinition(beanName, definition);
    }

    private String clientName(Class<?> interfaceType, LudwigRestClient annotation) {
        String name = StringUtils.hasText(annotation.name()) ? annotation.name() : annotation.value();
        if (!StringUtils.hasText(name)) {
            throw new IllegalStateException("@LudwigRestClient on " + interfaceType.getName()
                    + " must name a client from ludwig.rest-client.clients.");
        }
        // Resolved against the environment so `@LudwigRestClient("${billing.client:billing}")` works,
        // which is what a shared contracts artifact needs when two services reach the same API under
        // different client names.
        return environment.resolveRequiredPlaceholders(name);
    }

    private Class<?> resolve(String className) {
        try {
            return ClassUtils.forName(className, resourceLoader.getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Scanned @LudwigRestClient interface " + className
                    + " could not be loaded", ex);
        }
    }

    private Set<String> basePackages(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attributes =
                metadata.getAnnotationAttributes(EnableLudwigRestClients.class.getName());
        Set<String> packages = new LinkedHashSet<>();
        if (attributes != null) {
            AnnotationAttributes declared = AnnotationAttributes.fromMap(attributes);
            packages.addAll(List.of(declared.getStringArray("value")));
            packages.addAll(List.of(declared.getStringArray("basePackages")));
            for (Class<?> marker : declared.getClassArray("basePackageClasses")) {
                packages.add(ClassUtils.getPackageName(marker));
            }
            if (!packages.isEmpty()) {
                return packages;
            }
            // @EnableLudwigRestClients with nothing declared means "the package it is declared in",
            // which is the same convention @ComponentScan uses and the one people expect.
            packages.add(ClassUtils.getPackageName(metadata.getClassName()));
            return packages;
        }
        return autoConfigurationPackages(registry);
    }

    private Set<String> autoConfigurationPackages(BeanDefinitionRegistry registry) {
        if (!(registry instanceof org.springframework.beans.factory.BeanFactory beanFactory)
                || !AutoConfigurationPackages.has(beanFactory)) {
            // No @SpringBootApplication in sight - a plain ApplicationContext, or a test slice that
            // has not declared one. Scanning nothing is correct: scanning the classpath root would
            // walk every jar on it.
            return Set.of();
        }
        return new LinkedHashSet<>(new ArrayList<>(AutoConfigurationPackages.get(beanFactory)));
    }
}

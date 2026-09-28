package ru.ludwigandreas.restclient.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import ru.ludwigandreas.restclient.core.RestClientRegistry;

/**
 * Registers one injectable bean per configured client.
 *
 * <p>A {@code sync} client becomes a {@code RestClient} bean named {@code <name>RestClient} and
 * qualified {@code <name>}; an {@code async} one becomes a {@code WebClient} the same way. So a
 * service writes:
 *
 * <pre>{@code
 * BillingGateway(@Qualifier("billing") RestClient billing) { ... }
 * }</pre>
 *
 * <p>and nothing else - no {@code @Configuration}, no builder, no factory method. That is the second
 * of the three programming models, and it exists because a declarative interface is the wrong shape
 * for a call that is genuinely ad hoc.
 *
 * <h2>Why a bean-definition post-processor</h2>
 *
 * <p>The clients are named in configuration, so the set of beans is not known until the environment
 * is read - which is too late for {@code @Bean} methods and exactly what a
 * {@code BeanDefinitionRegistryPostProcessor} is for. The definitions carry an instance supplier
 * that delegates to {@link RestClientRegistry}, so a client that nobody injects is never built, and
 * a client injected twice is built once.
 *
 * <p>Properties are read here with {@code Binder} rather than from a bound
 * {@link RestClientProperties} bean: at this point in the lifecycle no {@code @ConfigurationProperties}
 * bean exists yet, and forcing one into existence would instantiate half the context early.
 */
public class NamedClientBeanRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(NamedClientBeanRegistrar.class);

    private Environment environment;
    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (!environment.getProperty(RestClientProperties.PREFIX + ".enabled", Boolean.class, true)) {
            return;
        }
        RestClientProperties properties = bind();
        properties.getClients().forEach((name, declared) -> {
            ClientProperties merged = ClientPropertiesMerger.resolve(properties.getDefaults(), declared);
            if (merged.getMode() == ClientMode.ASYNC) {
                register(registry, name, name + "WebClient", WebClient.class,
                        () -> registry().reactive(name));
            } else {
                register(registry, name, name + "RestClient", RestClient.class,
                        () -> registry().rest(name));
            }
        });
        log.debug("Registered {} named HTTP client bean(s)", properties.getClients().size());
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) throws BeansException {
        // Captured here rather than injected: this processor is created before the bean factory can
        // safely hand out beans, and the supplier below only runs long after both phases are done.
        this.beanFactory = factory;
    }

    private RestClientProperties bind() {
        return Binder.get(environment)
                .bind(RestClientProperties.PREFIX, Bindable.of(RestClientProperties.class))
                .orElseGet(RestClientProperties::new);
    }

    private RestClientRegistry registry() {
        return beanFactory.getBean(RestClientRegistry.class);
    }

    private void register(BeanDefinitionRegistry registry, String clientName, String beanName,
                          Class<?> type, java.util.function.Supplier<Object> supplier) {
        if (registry.containsBeanDefinition(beanName)) {
            // A service that declared its own bean of this name wins; the starter never overwrites
            // an explicit definition.
            return;
        }
        AbstractBeanDefinition definition = BeanDefinitionBuilder.genericBeanDefinition(type)
                .getBeanDefinition();
        definition.setInstanceSupplier(supplier);
        // The qualifier is what makes @Qualifier("billing") work; the bean name alone would only
        // match a parameter literally called "billingRestClient".
        definition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, clientName));
        definition.setLazyInit(true);
        registry.registerBeanDefinition(beanName, definition);
    }
}

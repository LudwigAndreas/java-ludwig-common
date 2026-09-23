package ru.ludwigandreas.restclient.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.restclient.registrar.LudwigRestClientsRegistrar;

/**
 * Wires the two things that turn configuration into injectable beans: the named
 * {@code RestClient}/{@code WebClient} definitions, and the {@code @LudwigRestClient} interface
 * proxies.
 *
 * <p>Both are bean-definition-time concerns rather than bean-creation-time ones, which is why they
 * live in their own auto-configuration: the set of beans depends on what the environment says, and
 * on what is on the classpath, neither of which a {@code @Bean} method can decide.
 *
 * <p>Importing {@link LudwigRestClientsRegistrar} here is what makes {@code @EnableLudwigRestClients}
 * optional. Imported from an auto-configuration, the registrar sees no {@code @EnableLudwigRestClients}
 * attributes and falls back to the application's auto-configuration packages - the behaviour a
 * service wants without writing anything. Declaring the annotation imports the same registrar a
 * second time with attributes, and Spring's import de-duplication means the annotated one wins.
 *
 * <p>The {@code RestClient}/{@code WebClient} bean definitions are registered by
 * {@link NamedClientBeanRegistrar}, declared as a static {@code @Bean} in
 * {@link RestClientAutoConfiguration} - a {@code BeanDefinitionRegistryPostProcessor} has to be
 * instantiated before the configuration class that declares it, and a non-static factory method
 * would force that class, and everything it injects, into existence far too early.
 */
@AutoConfiguration(after = RestClientAutoConfiguration.class)
@ConditionalOnProperty(prefix = RestClientProperties.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Import(LudwigRestClientsRegistrar.class)
public class RestClientInterfaceAutoConfiguration {
}

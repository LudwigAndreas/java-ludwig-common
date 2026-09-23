package ru.ludwigandreas.restclient.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import ru.ludwigandreas.restclient.annotation.EnableLudwigRestClients;
import ru.ludwigandreas.restclient.core.RestClientRegistry;
import ru.ludwigandreas.restclient.spi.LudwigRestClientCustomizer;

/** What the auto-configuration registers, and what it deliberately does not. */
class AutoConfigurationTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("a sync client becomes a RestClient bean injectable by qualifier")
    void registersQualifiedRestClientBeans() {
        RestClientTestSupport.runner(twoClients())
                .withUserConfiguration(Consumer.class)
                .run(context -> {
                    assertThat(context).hasBean("billingRestClient");
                    assertThat(context.getBean(Consumer.class).billing).isNotNull();
                });
    }

    @Test
    @DisplayName("an async client becomes a WebClient bean, not a RestClient one")
    void registersWebClientForAsyncMode() {
        RestClientTestSupport.runner(twoClients()).run(context -> {
            assertThat(context).hasBean("pricingWebClient");
            assertThat(context).doesNotHaveBean("pricingRestClient");
            assertThat(context.getBean("pricingWebClient")).isInstanceOf(WebClient.class);
        });
    }

    @Test
    @DisplayName("the registry refuses a blocking view of a reactive client, and says why")
    void registryEnforcesMode() {
        RestClientTestSupport.runner(twoClients()).run(context -> {
            RestClientRegistry registry = context.getBean(RestClientRegistry.class);

            assertThat(registry.names()).containsExactlyInAnyOrder("billing", "pricing");
            assertThat(registry.rest("billing")).isInstanceOf(RestClient.class);
            assertThat(registry.reactive("pricing")).isInstanceOf(WebClient.class);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.rest("pricing"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("registry.reactive");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.rest("nope"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("billing");
        });
    }

    @Test
    @DisplayName("a declarative interface is registered and calls through the pipeline")
    void registersDeclarativeInterfaces() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("invoice-42"));

        RestClientTestSupport.runner(twoClients())
                .withUserConfiguration(EnableInterfaces.class)
                .run(context -> {
                    BillingApi api = context.getBean(BillingApi.class);
                    assertThat(api.invoice("42")).isEqualTo("invoice-42");
                });
    }

    @Test
    @DisplayName("a reactive return type on a sync client fails at startup, naming the method")
    void rejectsReturnTypeMismatchAtStartup() {
        RestClientTestSupport.runner(twoClients())
                .withUserConfiguration(EnableMismatched.class)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("MismatchedApi")
                        .hasMessageContaining("invoice")
                        .hasMessageContaining("billing"));
    }

    @Test
    @DisplayName("a customizer runs after every YAML-derived setting")
    void customizersRunLast() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RestClientTestSupport.runner(twoClients())
                .withUserConfiguration(CustomizerConfiguration.class)
                .run(context -> context.getBean(RestClientRegistry.class).rest("billing")
                        .get().uri("/x").retrieve().body(String.class));

        // default-headers set X-Platform=ludwig; the customizer overrides it, which is the whole
        // point of the customizer being the later precedence level.
        assertThat(server.takeRequest().getHeader("X-Platform")).isEqualTo("customized");
    }

    @Test
    @DisplayName("enabled=false registers nothing at all")
    void masterSwitchRegistersNothing() {
        RestClientTestSupport.runner("ludwig.rest-client.enabled=false")
                .withPropertyValues(twoClients())
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RestClientRegistry.class);
                    assertThat(context).doesNotHaveBean("billingRestClient");
                });
    }

    @Test
    @DisplayName("the default User-Agent names the service and the client")
    void sendsAnIdentifyingUserAgent() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        RestClientTestSupport.runner(twoClients())
                .withPropertyValues("spring.application.name=catalog")
                .run(context -> context.getBean(RestClientRegistry.class).rest("billing")
                        .get().uri("/x").retrieve().body(String.class));

        assertThat(server.takeRequest().getHeader("User-Agent"))
                .isEqualTo("catalog (ludwig-rest-client; client=billing)");
    }

    private String[] twoClients() {
        return RestClientTestSupport.props(
                RestClientTestSupport.fastClient("billing", server.url("/").toString(),
                        "ludwig.rest-client.clients.billing.default-headers.X-Platform=ludwig"),
                RestClientTestSupport.fastClient("pricing", server.url("/").toString(),
                        "ludwig.rest-client.clients.pricing.mode=async"));
    }

    /** Proves qualifier injection works the way the README says it does. */
    @Configuration(proxyBeanMethods = false)
    static class Consumer {

        private final RestClient billing;

        Consumer(@Qualifier("billing") RestClient billing) {
            this.billing = billing;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableLudwigRestClients(basePackageClasses = BillingApi.class)
    static class EnableInterfaces {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableLudwigRestClients(basePackageClasses = ru.ludwigandreas.restclient.badclient.MismatchedApi.class)
    static class EnableMismatched {
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomizerConfiguration {

        @Bean
        LudwigRestClientCustomizer platformHeaderCustomizer() {
            return new LudwigRestClientCustomizer() {
                @Override
                public boolean supports(String clientName) {
                    return "billing".equals(clientName);
                }

                @Override
                public void customize(String clientName, RestClient.Builder builder) {
                    builder.defaultHeader("X-Platform", "customized");
                }
            };
        }
    }
}

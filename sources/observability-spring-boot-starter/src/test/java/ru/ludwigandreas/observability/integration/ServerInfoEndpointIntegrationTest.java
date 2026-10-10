package ru.ludwigandreas.observability.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ru.ludwigandreas.observability.config.ObservabilityCoreAutoConfiguration;
import ru.ludwigandreas.observability.config.ObservabilityWebAutoConfiguration;
import ru.ludwigandreas.observability.web.ServerInfoController;

/**
 * {@code GET /server/info} as a service gets it from the starter: present on a servlet application,
 * gone when switched off, and never registered where there is no servlet container.
 *
 * <p>The requests go through Spring MVC and Boot's own Jackson configuration rather than calling the
 * controller, so that what is asserted is the document a client receives.
 */
class ServerInfoEndpointIntegrationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    ObservabilityCoreAutoConfiguration.class,
                    ObservabilityWebAutoConfiguration.class))
            .withPropertyValues(
                    "ludwig.observability.service.name=product-catalog",
                    "ludwig.observability.service.version=1.4.2",
                    "ludwig.observability.service.environment=prod");

    @Test
    void describesTheServiceOnAServletApplication() {
        webRunner.withPropertyValues(
                        "ludwig.observability.build.abbreviated-commit-id=c1fc5b8",
                        "ludwig.observability.build.branch=release-train",
                        "ludwig.observability.build.timestamp=2026-09-16T00:00:00Z")
                .run(context -> mockMvc(context).perform(get(ServerInfoController.PATH))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.service").value("product-catalog"))
                        .andExpect(jsonPath("$.version").value("1.4.2"))
                        .andExpect(jsonPath("$.environment").value("prod"))
                        .andExpect(jsonPath("$.commit").value("c1fc5b8"))
                        .andExpect(jsonPath("$.built").value("2026-09-16"))
                        .andExpect(jsonPath("$.branch").doesNotExist()));
    }

    @Test
    void answersWithoutBuildProvenanceAndLeavesItOut() {
        webRunner.run(context -> mockMvc(context).perform(get(ServerInfoController.PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("product-catalog"))
                .andExpect(jsonPath("$.commit").doesNotExist())
                .andExpect(jsonPath("$.built").doesNotExist()));
    }

    @Test
    void isUnmappedWhenSwitchedOff() {
        webRunner.withPropertyValues("ludwig.observability.server.info.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ServerInfoController.class);
                    mockMvc(context).perform(get(ServerInfoController.PATH)).andExpect(status().isNotFound());
                });
    }

    @Test
    void isUnmappedWhenTheWholeStarterIsSwitchedOff() {
        webRunner.withPropertyValues("ludwig.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ServerInfoController.class);
                    mockMvc(context).perform(get(ServerInfoController.PATH)).andExpect(status().isNotFound());
                });
    }

    @Test
    void isNotRegisteredWithoutAServletContainer() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ObservabilityCoreAutoConfiguration.class,
                        ObservabilityWebAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ServerInfoController.class));
    }

    private static MockMvc mockMvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }
}

package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import ru.ludwigandreas.webcore.config.WebCoreLocalizationAutoConfiguration;
import ru.ludwigandreas.webcore.config.WebCoreProblemAutoConfiguration;
import ru.ludwigandreas.webcore.config.WebCoreWebMvcAutoConfiguration;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;
import ru.ludwigandreas.webcore.problem.ProblemMessages;
import ru.ludwigandreas.webcore.problem.ProblemStatus;
import ru.ludwigandreas.webcore.problem.mapper.SecurityProblemMapper;
import ru.ludwigandreas.webcore.trace.TraceIdProvider;
import ru.ludwigandreas.webcore.web.ApiExceptionHandler;

/**
 * The starter's back-off behaviour. A library that cannot be switched off or overridden is a library
 * a service eventually has to fork, so each escape hatch is pinned: every bean yields to an
 * application's own, the advice can be disabled without losing the pipeline, and the whole module
 * can be turned off.
 */
class WebCoreAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebCoreLocalizationAutoConfiguration.class,
                    WebCoreProblemAutoConfiguration.class,
                    WebCoreWebMvcAutoConfiguration.class,
                    MessageSourceAutoConfiguration.class,
                    ValidationAutoConfiguration.class));

    private final ApplicationContextRunner plainRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebCoreLocalizationAutoConfiguration.class,
                    WebCoreProblemAutoConfiguration.class,
                    WebCoreWebMvcAutoConfiguration.class));

    @Test
    @DisplayName("a web application gets the whole pipeline with no configuration")
    void wiresThePipelineByDefault() {
        webRunner.run(context -> assertThat(context)
                .hasSingleBean(ProblemMessages.class)
                .hasSingleBean(ProblemDetailFactory.class)
                .hasSingleBean(ProblemMapperRegistry.class)
                .hasSingleBean(ApiExceptionHandler.class)
                .hasSingleBean(TraceIdProvider.class)
                .hasBean("messageSource")
                .hasSingleBean(LocaleResolver.class));
    }

    @Test
    @DisplayName("the starter contributes its own bundle through the same SPI modules use")
    void contributesItsOwnBundle() {
        webRunner.run(context -> assertThat(context.getBean(ProblemMessages.class).basenames())
                .contains("i18n/ludwig-web-messages"));
    }

    @Test
    @DisplayName("a non-web module gets the pipeline without the advice or the locale resolver")
    void skipsMvcBeansOutsideAWebApplication() {
        // The case that keeps a Kafka consumer or a batch job from needing Spring MVC just to throw
        // a LocalizedException.
        plainRunner.run(context -> assertThat(context)
                .hasSingleBean(ProblemMessages.class)
                .hasSingleBean(ProblemDetailFactory.class)
                .doesNotHaveBean(ApiExceptionHandler.class));
    }

    @Test
    @DisplayName("the master switch removes everything")
    void masterSwitchDisablesEverything() {
        webRunner.withPropertyValues("ludwig.web.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(ProblemMessages.class)
                .doesNotHaveBean(ProblemDetailFactory.class)
                .doesNotHaveBean(ApiExceptionHandler.class));
    }

    @Test
    @DisplayName("the advice can be switched off while the localized pipeline stays available")
    void adviceCanBeDisabledWithoutLosingThePipeline() {
        // For a service that renders problems from its own advice but wants the message chain,
        // the mappers and the renderer underneath it.
        webRunner.withPropertyValues("ludwig.web.problem.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(ApiExceptionHandler.class)
                .hasSingleBean(ProblemDetailFactory.class)
                .hasSingleBean(ProblemMessages.class));
    }

    @Test
    @DisplayName("an application's own advice bean replaces the starter's")
    void applicationAdviceWins() {
        webRunner.withUserConfiguration(CustomAdviceConfiguration.class).run(context -> assertThat(context)
                .hasSingleBean(ApiExceptionHandler.class)
                .getBean(ApiExceptionHandler.class)
                .isSameAs(context.getBean("customApiExceptionHandler")));
    }

    @Test
    @DisplayName("an application's own MessageSource is used, and Boot's is not added alongside")
    void applicationMessageSourceWins() {
        webRunner.withUserConfiguration(CustomMessageSourceConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(MessageSource.class)
                        .getBean("messageSource")
                        .isInstanceOf(StaticMessageSource.class));
    }

    @Test
    @DisplayName("i18n can be handed back to Spring Boot's defaults on its own")
    void i18nCanBeDelegatedBackToBoot() {
        webRunner.withPropertyValues("ludwig.web.i18n.enabled=false").run(context -> assertThat(context)
                // Boot's own MessageSourceAutoConfiguration takes over.
                .hasBean("messageSource")
                .doesNotHaveBean(LocaleResolver.class)
                // The problem pipeline is unaffected.
                .hasSingleBean(ApiExceptionHandler.class));
    }

    @Test
    @DisplayName("a contributed bundle joins the resolution chain")
    void contributedBundlesJoinTheChain() {
        webRunner.withUserConfiguration(ContributedBundleConfiguration.class)
                .run(context -> assertThat(context.getBean(ProblemMessages.class).basenames())
                        .containsExactly("i18n/test-module-messages", "i18n/ludwig-web-messages"));
    }

    @Test
    @DisplayName("an application's mapper outranks the starter's for the same exception type")
    void applicationMapperOutranksTheStarters() {
        webRunner.withUserConfiguration(OverridingMapperConfiguration.class).run(context -> {
            ProblemMapperRegistry registry = context.getBean(ProblemMapperRegistry.class);

            assertThat(context).hasSingleBean(SecurityProblemMapper.class);
            assertThat(registry.resolve(new org.springframework.security.access.AccessDeniedException("no")))
                    .get()
                    .extracting(ProblemDefinition::code)
                    .isEqualTo("application.forbidden");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAdviceConfiguration {
        @Bean
        ApiExceptionHandler customApiExceptionHandler(
                ProblemDetailFactory problems, ProblemMapperRegistry mappers) {
            return new ApiExceptionHandler(problems, mappers, 0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomMessageSourceConfiguration {
        @Bean
        MessageSource messageSource() {
            return new StaticMessageSource();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ContributedBundleConfiguration {
        @Bean
        ProblemMessageBundle testModuleBundle() {
            return ProblemMessageBundle.of("i18n/test-module-messages", 10);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OverridingMapperConfiguration {
        @Bean
        ExceptionProblemMapper applicationForbiddenMapper() {
            // The default order of 0 is all it takes: a module's mapper sits at
            // DEFAULT_MODULE_ORDER precisely so an application never has to guess a number.
            return ExceptionProblemMapper.forType(
                    org.springframework.security.access.AccessDeniedException.class,
                    e -> ProblemDefinition.of(ProblemStatus.FORBIDDEN, "application.forbidden"),
                    0);
        }
    }
}

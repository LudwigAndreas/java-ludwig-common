package ru.ludwigandreas.asyncapi.config;

import io.github.springwolf.core.asyncapi.scanners.classes.spring.ComponentClassScanner;
import io.github.springwolf.core.configuration.SpringwolfAutoConfiguration;
import io.github.springwolf.core.configuration.docket.AsyncApiDocketService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.asyncapi.document.IntrospectableComponentClassScanner;

/**
 * Replaces Springwolf's component scanner before Springwolf registers its own, which it does only when none exists.
 */
@AutoConfiguration(before = SpringwolfAutoConfiguration.class)
@ConditionalOnClass(ComponentClassScanner.class)
public class AsyncApiScannerAutoConfiguration {

    /**
     * The scanner.
     *
     * @param docket      Springwolf's docket
     * @param environment the environment
     * @return the scanner
     */
    @Bean
    @ConditionalOnMissingBean(ComponentClassScanner.class)
    public ComponentClassScanner componentClassScanner(AsyncApiDocketService docket, Environment environment) {
        return new IntrospectableComponentClassScanner(docket, environment);
    }
}

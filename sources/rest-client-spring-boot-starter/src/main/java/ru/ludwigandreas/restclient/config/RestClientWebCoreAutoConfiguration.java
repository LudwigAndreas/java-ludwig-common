package ru.ludwigandreas.restclient.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.restclient.error.RestClientProblemMapper;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;

/**
 * Renders an escaped dependency failure as the platform's own problem document, when
 * web-core-spring-boot-starter is present.
 *
 * <p>This is the whole of the integration, and its smallness is the point: web-core already owns how
 * a failure becomes HTTP, so this module contributes one mapper rather than a second
 * {@code @RestControllerAdvice} that would render almost-but-not-quite the same document.
 */
@AutoConfiguration(after = RestClientAutoConfiguration.class)
@ConditionalOnClass(ExceptionProblemMapper.class)
@ConditionalOnProperty(prefix = RestClientProperties.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RestClientWebCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RestClientProblemMapper ludwigRestClientProblemMapper() {
        return new RestClientProblemMapper();
    }
}

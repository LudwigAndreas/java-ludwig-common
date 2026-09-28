package ru.ludwigandreas.webcore.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;
import ru.ludwigandreas.webcore.problem.mapper.BindingProblemMapper;
import ru.ludwigandreas.webcore.problem.mapper.HandlerMethodValidationProblemMapper;
import ru.ludwigandreas.webcore.problem.mapper.SpringWebProblemMapper;
import ru.ludwigandreas.webcore.web.ApiExceptionHandler;

/**
 * The Spring MVC half of the pipeline: the framework's own exceptions, request validation, and the
 * one advice that renders everything.
 *
 * <p>Separate from {@link WebCoreProblemAutoConfiguration} and conditional on a servlet web
 * application, so pulling this starter into a module that is not a web application - a batch job or
 * a message consumer that still wants localized {@code LocalizedException}s - never drags Spring MVC
 * onto its classpath.
 */
@AutoConfiguration(after = WebCoreProblemAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcConfigurer.class)
@ConditionalOnProperty(prefix = "ludwig.web", name = "enabled", matchIfMissing = true)
public class WebCoreWebMvcAutoConfiguration {

    /** Spring MVC's own exceptions, localized under this starter's codes. */
    @Bean
    public ExceptionProblemMapper ludwigSpringWebProblemMapper() {
        return new SpringWebProblemMapper();
    }

    /** A rejected {@code @Valid @RequestBody}, reported field by field. */
    @Bean
    public ExceptionProblemMapper ludwigBindingProblemMapper() {
        return new BindingProblemMapper();
    }

    /** A failed constraint on a {@code @RequestParam} or {@code @PathVariable}. */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.method.annotation.HandlerMethodValidationException")
    public ExceptionProblemMapper ludwigHandlerMethodValidationProblemMapper() {
        return new HandlerMethodValidationProblemMapper();
    }

    /**
     * The single advice.
     *
     * <p>Not registered when the application declares its own {@link ApiExceptionHandler} bean, and
     * switchable off entirely with {@code ludwig.web.problem.enabled=false} - for a service that
     * renders problems from its own advice but still wants the localized pipeline underneath it.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ludwig.web.problem", name = "enabled", matchIfMissing = true)
    public ApiExceptionHandler ludwigApiExceptionHandler(
            ProblemDetailFactory problems, ProblemMapperRegistry mappers, WebCoreProperties properties) {
        return new ApiExceptionHandler(problems, mappers, properties.getProblem().getAdviceOrder());
    }
}

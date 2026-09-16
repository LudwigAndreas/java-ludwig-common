package ru.ludwigandreas.webcore.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * What an application adds to extend the pipeline: a mapper for an exception the starter has never
 * heard of, and a trace id in the MDC. Both are single beans, which is the point.
 */
@TestConfiguration
public class TestContributionConfiguration {

    static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @Bean
    public ExceptionProblemMapper quotaExceededProblemMapper() {
        return ExceptionProblemMapper.forType(
                QuotaExceededException.class,
                e -> ProblemDefinition.of(
                                ProblemStatus.TOO_MANY_REQUESTS, "error.quota.exceeded", e.limit())
                        .withProperty("limit", e.limit()));
    }

    /** Stands in for Micrometer Tracing or an OpenTelemetry agent, which both write this key. */
    @Bean
    public OncePerRequestFilter testTracingFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response,
                    jakarta.servlet.FilterChain chain)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                org.slf4j.MDC.put("traceId", TRACE_ID);
                try {
                    chain.doFilter(request, response);
                } finally {
                    org.slf4j.MDC.remove("traceId");
                }
            }
        };
    }
}

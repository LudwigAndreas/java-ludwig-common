package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.ludwigandreas.observability.config.ObservabilityProperties;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.correlation.CorrelationIdResolver;
import ru.ludwigandreas.observability.tracing.TraceContextAccessor;
import ru.ludwigandreas.observability.web.CorrelationIdFilter;

/** One request in, one correlation id established, logged, echoed and cleaned up. */
class CorrelationIdFilterTest {

    private final ObservabilityProperties.Correlation properties = new ObservabilityProperties.Correlation();
    private final CorrelationContext context = new CorrelationContext(properties.getMdcKey());
    private final CorrelationIdFilter filter = new CorrelationIdFilter(
            context,
            new CorrelationIdResolver(properties.getAllowedPattern(), properties.getMaxLength(), true),
            // No tracer: correlation must work with tracing switched off, which is a supported setup.
            new TraceContextAccessor(null),
            properties);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void adoptsAWellFormedInboundIdAndEchoesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/4711");
        request.addHeader("X-Correlation-Id", "order-4711");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("order-4711");
    }

    @Test
    void fallsBackToTheSecondaryInboundHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("X-Request-Id", "edge-generated-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("edge-generated-id");
    }

    @Test
    void generatesAnIdWhenTheCallerSentNone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), response, new MockFilterChain());

        assertThat(response.getHeader("X-Correlation-Id")).hasSize(32);
    }

    @Test
    void makesTheIdVisibleToTheMdcForTheDurationOfTheRequestOnly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("X-Correlation-Id", "order-4711");
        FilterChain assertingChain = (req, res) ->
                assertThat(MDC.get("correlationId")).isEqualTo("order-4711");

        filter.doFilter(request, new MockHttpServletResponse(), assertingChain);

        // Left bound, the id would mislabel every later request handled on this pooled thread.
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void restoresTheIdEvenWhenTheHandlerThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        FilterChain failingChain = (req, res) -> {
            throw new IllegalStateException("handler blew up");
        };

        try {
            filter.doFilter(request, new MockHttpServletResponse(), failingChain);
        } catch (Exception expected) {
            // The failure is the point of the test; what matters is the state left behind.
        }

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void replacesAnInboundIdThatCouldSplitTheResponseHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
        request.addHeader("X-Correlation-Id", "evil\r\nSet-Cookie: admin=1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Correlation-Id")).hasSize(32).doesNotContain("Set-Cookie");
    }

    @Test
    void omitsTheTraceHeaderWhenNothingIsTracing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), response, new MockFilterChain());

        // An all-zero placeholder id would look like a real trace and resolve to nothing.
        assertThat(response.getHeader("X-Trace-Id")).isNull();
    }

    @Test
    void skipsTheEchoHeaderWhenItIsSwitchedOff() throws Exception {
        properties.setIncludeResponseHeader(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/orders"), response, new MockFilterChain());

        assertThat(response.getHeader("X-Correlation-Id")).isNull();
    }

    @Test
    void sitsImmediatelyAfterSpringsObservationFilterSoTheServerSpanAlreadyExists() {
        // ServerHttpObservationFilter registers at HIGHEST_PRECEDENCE + 1. Sharing that order would
        // leave the relative position down to registration order; +2 states it.
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
    }

    @Test
    void acceptsEveryHeaderItIsConfiguredToRead() {
        assertThat(properties.getAdditionalInboundHeaders()).isEqualTo(List.of("X-Request-Id"));
    }
}

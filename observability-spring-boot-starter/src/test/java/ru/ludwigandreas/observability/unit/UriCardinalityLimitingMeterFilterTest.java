package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.observability.metrics.UriCardinalityLimitingMeterFilter;

/** The filter that stands between an attacker's URL generator and the registry's heap. */
class UriCardinalityLimitingMeterFilterTest {

    private static final String METER = "http.server.requests";

    @Test
    void keepsTheFirstUrisAndFoldsTheRestIntoOther() {
        UriCardinalityLimitingMeterFilter filter = new UriCardinalityLimitingMeterFilter(METER, 2);

        assertThat(mappedUri(filter, "/orders/{id}")).isEqualTo("/orders/{id}");
        assertThat(mappedUri(filter, "/customers/{id}")).isEqualTo("/customers/{id}");
        assertThat(mappedUri(filter, "/scan-attempt-1")).isEqualTo("OTHER");
        assertThat(mappedUri(filter, "/scan-attempt-2")).isEqualTo("OTHER");
    }

    @Test
    void keepsAdmittingUrisItHasAlreadySeenAfterTheCapIsReached() {
        UriCardinalityLimitingMeterFilter filter = new UriCardinalityLimitingMeterFilter(METER, 1);
        mappedUri(filter, "/orders/{id}");
        mappedUri(filter, "/overflow");

        // A real endpoint admitted before the cap must not start reporting as OTHER afterwards, or a
        // burst of 404s would erase the metrics of the endpoints that were working fine.
        assertThat(mappedUri(filter, "/orders/{id}")).isEqualTo("/orders/{id}");
    }

    @Test
    void preservesTheOtherTagsWhenItRewritesTheUri() {
        UriCardinalityLimitingMeterFilter filter = new UriCardinalityLimitingMeterFilter(METER, 0);

        Meter.Id mapped = filter.map(id(METER, Tags.of(
                "uri", "/anything", "method", "GET", "status", "500", "outcome", "SERVER_ERROR")));

        // Regression guard. Meter.Id.replaceTags substitutes the ENTIRE tag set, so using it here
        // would silently drop method/status/outcome - and with them the "R" and "E" of RED, leaving a
        // metric that counts requests but can no longer tell a failure from a success.
        assertThat(mapped.getTag("uri")).isEqualTo("OTHER");
        assertThat(mapped.getTag("method")).isEqualTo("GET");
        assertThat(mapped.getTag("status")).isEqualTo("500");
        assertThat(mapped.getTag("outcome")).isEqualTo("SERVER_ERROR");
    }

    @Test
    void leavesUnrelatedMetersAlone() {
        UriCardinalityLimitingMeterFilter filter = new UriCardinalityLimitingMeterFilter(METER, 0);

        Meter.Id mapped = filter.map(id("jvm.memory.used", Tags.of("area", "heap")));

        assertThat(mapped.getTag("area")).isEqualTo("heap");
        assertThat(mapped.getTag("uri")).isNull();
    }

    @Test
    void leavesAMatchingMeterAloneWhenItCarriesNoUriTag() {
        UriCardinalityLimitingMeterFilter filter = new UriCardinalityLimitingMeterFilter(METER, 0);

        Meter.Id mapped = filter.map(id(METER, Tags.of("method", "GET")));

        assertThat(mapped.getTag("uri")).isNull();
    }

    @Test
    void boundsTheRegistryWhenEveryRequestBringsANewUri() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new UriCardinalityLimitingMeterFilter(METER, 10));

        for (int i = 0; i < 1_000; i++) {
            registry.counter(METER, "uri", "/probe-" + i, "status", "404").increment();
        }

        // 10 admitted URIs plus the single OTHER series - not 1000. This is the whole point of the
        // filter: the series count is bounded by configuration rather than by the caller.
        assertThat(registry.find(METER).counters()).hasSize(11);
    }

    private String mappedUri(UriCardinalityLimitingMeterFilter filter, String uri) {
        return filter.map(id(METER, Tags.of("uri", uri))).getTag("uri");
    }

    private Meter.Id id(String name, Tags tags) {
        return new Meter.Id(name, tags, null, null, Meter.Type.TIMER);
    }
}

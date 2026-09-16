package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.observability.correlation.CorrelationIdResolver;

/** The boundary where an untrusted header becomes something safe to log, echo and forward. */
class CorrelationIdResolverTest {

    private final CorrelationIdResolver resolver = new CorrelationIdResolver("[A-Za-z0-9_.:-]+", 128, true);

    @Test
    void acceptsAWellFormedInboundId() {
        assertThat(resolver.resolve(List.of("order-4711"), () -> null)).isEqualTo("order-4711");
    }

    @Test
    void takesTheFirstNonBlankCandidateInPriorityOrder() {
        assertThat(resolver.resolve(Arrays.asList(null, "", "from-fallback-header"), () -> null))
                .isEqualTo("from-fallback-header");
    }

    @Test
    void replacesRatherThanRepairsAnIdContainingIllegalCharacters() {
        // A CRLF here would be an HTTP response-splitting bug once the id is echoed back, so the value
        // is discarded whole. Repairing it by stripping characters would leave the attacker in control
        // of whatever survived, and there is nothing worth preserving - a malformed id correlates
        // with nothing anyway.
        String resolved = resolver.resolve(List.of("evil\r\nSet-Cookie: admin=1"), () -> null);

        assertThat(resolved).doesNotContain("\r").doesNotContain("\n").hasSize(32);
    }

    @Test
    void replacesAnIdThatExceedsTheLengthCap() {
        String resolved = resolver.resolve(List.of("x".repeat(129)), () -> null);

        assertThat(resolved).hasSize(32);
    }

    @Test
    void acceptsAnIdExactlyAtTheLengthCap() {
        String atCap = "x".repeat(128);

        assertThat(resolver.resolve(List.of(atCap), () -> null)).isEqualTo(atCap);
    }

    @Test
    void adoptsTheTraceIdWhenNothingWasSentSoBothIdsMatchForLocallyOriginatedTraffic() {
        String traceId = "0af7651916cd43dd8448eb211c80319c";

        assertThat(resolver.resolve(List.of(), () -> traceId)).isEqualTo(traceId);
    }

    @Test
    void generatesATraceIdShapedValueWhenThereIsNoTraceEither() {
        String generated = resolver.resolve(List.of(), () -> null);

        assertThat(generated).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void generatesADifferentValueEachTime() {
        assertThat(resolver.generate()).isNotEqualTo(resolver.generate());
    }

    @Test
    void returnsNullWhenGenerationIsDisabledAndNothingUsableArrived() {
        CorrelationIdResolver noGeneration = new CorrelationIdResolver("[A-Za-z0-9]+", 128, false);

        assertThat(noGeneration.resolve(List.of(), () -> null)).isNull();
    }

    @Test
    void doesNotFallBackToALaterHeaderAfterRejectingAnEarlierOne() {
        // Deliberate: a caller that sent a malformed id in the primary header should not be able to
        // have a secondary header quietly used instead - that turns header precedence into something
        // an attacker can steer.
        String resolved = resolver.resolve(List.of("bad value!", "clean-fallback"), () -> null);

        assertThat(resolved).isNotEqualTo("clean-fallback").hasSize(32);
    }
}

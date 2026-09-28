package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.observability.tracing.ForceableSampler;
import ru.ludwigandreas.observability.tracing.ForcedSamplingHint;

/** The escape hatch from head sampling, and the limits placed on it. */
class ForceableSamplerTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private final ForceableSampler sampler = new ForceableSampler(Sampler.alwaysOff());

    @Test
    void delegatesWhenNothingForcesSampling() {
        assertThat(decide(Context.root())).isEqualTo(SamplingDecision.DROP);
    }

    @Test
    void samplesALocalRootSpanWhenForced() {
        try (ForcedSamplingHint.Scope ignored = ForcedSamplingHint.force()) {
            assertThat(decide(Context.root())).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        }
    }

    @Test
    void honoursTheCallersDecisionEvenWhenForcedSoNoTraceStartsInTheMiddle() {
        Context withRemoteParent = Context.root().with(Span.wrap(SpanContext.createFromRemoteParent(
                TRACE_ID, "b7ad6b7169203331", TraceFlags.getDefault(), TraceState.getDefault())));

        try (ForcedSamplingHint.Scope ignored = ForcedSamplingHint.force()) {
            // Overriding here would record a child whose parent was never exported, producing a trace
            // that appears to begin halfway down a call chain.
            assertThat(decide(withRemoteParent)).isEqualTo(SamplingDecision.DROP);
        }
    }

    @Test
    void clearsTheHintWhenTheScopeClosesSoAPooledThreadDoesNotKeepForcing() {
        try (ForcedSamplingHint.Scope ignored = ForcedSamplingHint.force()) {
            assertThat(ForcedSamplingHint.isForced()).isTrue();
        }

        assertThat(ForcedSamplingHint.isForced()).isFalse();
        assertThat(decide(Context.root())).isEqualTo(SamplingDecision.DROP);
    }

    @Test
    void describesItselfInTermsOfItsDelegate() {
        assertThat(sampler.getDescription()).contains("ForceableSampler").contains("AlwaysOffSampler");
    }

    private SamplingDecision decide(Context parentContext) {
        return sampler.shouldSample(parentContext, TRACE_ID, "GET /orders", SpanKind.SERVER,
                Attributes.empty(), List.of()).getDecision();
    }
}

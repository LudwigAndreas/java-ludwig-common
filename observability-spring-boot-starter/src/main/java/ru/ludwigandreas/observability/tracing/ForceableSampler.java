package ru.ludwigandreas.observability.tracing;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;

/**
 * The configured sampler, plus an on-demand override for the request being investigated.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>Production traces at a probability - a few percent - because tracing every request costs more
 * in export bandwidth and backend storage than the traces are worth. That is the right trade-off
 * until someone reports a specific failing request, at which point the odds are overwhelming that
 * this particular request was not sampled, and the only remedies are to raise the global probability
 * (a fleet-wide cost increase, usually requiring a rollout) or to ask the reporter to try again
 * until they get lucky.
 *
 * <p>So this sampler takes the normal decision from its delegate, and returns
 * {@link SamplingResult#recordAndSample()} instead when {@link ForcedSamplingHint} is set for the
 * current thread - letting one request be traced in full without changing anything for the rest.
 *
 * <p>The override only applies to a <em>local root</em> span. When the caller already made a
 * sampling decision, the delegate is a parent-based sampler and that decision must be honoured:
 * overriding it would record a child whose parent was never exported, producing a trace that appears
 * to start in the middle of a call chain. In other words the force header decides traces, not spans,
 * and it only does so at the service where the trace begins.
 */
public class ForceableSampler implements Sampler {

    private final Sampler delegate;

    public ForceableSampler(Sampler delegate) {
        this.delegate = delegate;
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name, SpanKind spanKind,
            Attributes attributes, List<LinkData> parentLinks) {
        if (ForcedSamplingHint.isForced() && isLocalRoot(parentContext)) {
            return SamplingResult.recordAndSample();
        }
        return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    /**
     * True when no valid remote or local parent is in scope, i.e. this span begins the trace here.
     *
     * <p>{@code Span.fromContext} returns an invalid span rather than null for an empty context,
     * which is why the check is on validity rather than on nullness.
     */
    private boolean isLocalRoot(Context parentContext) {
        return !io.opentelemetry.api.trace.Span.fromContext(parentContext).getSpanContext().isValid();
    }

    @Override
    public String getDescription() {
        return "ForceableSampler{" + delegate.getDescription() + "}";
    }
}

package ru.ludwigandreas.common.retryer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RetryingCallMetricsTest {

    @Test
    void recordsOneAttemptAndSuccessOutcomeWhenFirstTryWorks() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        String result = RetryingCall.with(new RetryPolicy())
                .withMetrics(registry, "test-op")
                .get(() -> "ok");

        assertEquals("ok", result);
        assertEquals(1.0, registry.get("retry.attempts").tag("operation", "test-op").counter().count());
        assertEquals(1L, registry.get("retry.call")
                .tag("operation", "test-op")
                .tag("outcome", "success")
                .timer().count());
    }

    @Test
    void recordsOneAttemptPerRetryAndSuccessOutcomeOnceTheOperationEventuallySucceeds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger calls = new AtomicInteger();

        RetryPolicy policy = new RetryPolicy().retryOn(IOException.class).withMaxRetries(5);

        String result = RetryingCall.with(policy)
                .withMetrics(registry, "test-op")
                .get(() -> {
                    if (calls.incrementAndGet() < 3) {
                        throw new IOException("not yet");
                    }
                    return "ok";
                });

        assertEquals("ok", result);
        assertEquals(3.0, registry.get("retry.attempts").tag("operation", "test-op").counter().count());
        assertEquals(1L, registry.get("retry.call")
                .tag("operation", "test-op")
                .tag("outcome", "success")
                .timer().count());
    }

    @Test
    void recordsFailureOutcomeTaggedWithTheExceptionTypeWhenRetriesAreExhausted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetryPolicy policy = new RetryPolicy().retryOn(IOException.class).withMaxRetries(2);

        try {
            RetryingCall.with(policy)
                    .withMetrics(registry, "test-op")
                    .run(() -> {
                        throw new IOException("always fails");
                    });
        } catch (RuntimeException expected) {
            // exhausted retries rethrow the last exception
        }

        assertEquals(3.0, registry.get("retry.attempts").tag("operation", "test-op").counter().count());
        assertEquals(1L, registry.get("retry.call")
                .tag("operation", "test-op")
                .tag("outcome", "failure")
                .tag("exception", "IOException")
                .timer().count());
    }

    @Test
    void doesNothingWhenMetricsWereNeverConfigured() {
        assertDoesNotThrow(() -> RetryingCall.with(new RetryPolicy()).get(() -> "ok"));
    }
}

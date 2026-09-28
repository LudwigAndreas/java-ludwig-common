package ru.ludwigandreas.messaging.metrics;

/**
 * Does nothing, and is always present so that nothing in the container wiring has to null-check.
 *
 * <p>The alternative - an {@code ObjectProvider<MessagingMetrics>} at every call site - puts a
 * conditional in front of every measurement, and the measurements are on the error path, which is
 * exactly where a stray null check is least likely to be exercised by a test.
 */
public class NoopMessagingMetrics implements MessagingMetrics {

    @Override
    public void recordConsumed(String topic) {
        // Intentionally empty - see the class comment.
    }

    @Override
    public void recordRetry(String topic) {
        // Intentionally empty.
    }

    @Override
    public void recordDeadLettered(String topic, String deadLetterTopic, String exception) {
        // Intentionally empty.
    }

    @Override
    public void recordDropped(String topic) {
        // Intentionally empty.
    }

    @Override
    public void registerSilence(String topic, java.util.function.IntSupplier silent) {
        // Intentionally empty.
    }
}

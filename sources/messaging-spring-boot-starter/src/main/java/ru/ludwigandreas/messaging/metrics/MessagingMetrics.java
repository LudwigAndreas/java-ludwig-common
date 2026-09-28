package ru.ludwigandreas.messaging.metrics;

/**
 * Instrumentation for everything this module does.
 *
 * <p>{@link NoopMessagingMetrics} is always registered, so nothing in the container wiring has to
 * null-check; {@link MicrometerMessagingMetrics} replaces it when Micrometer is present and
 * {@code ludwig.messaging.metrics.enabled} is true. The same arrangement {@code OutboxMetrics},
 * {@code IngestMetrics}, {@code ExportMetrics} and {@code IdempotencyMetrics} use.
 *
 * <h2>Consumer lag is bound elsewhere, on purpose</h2>
 *
 * <p>Lag per topic, partition and group is not a counter this interface owns. It comes from
 * {@code MicrometerConsumerListener}, which binds the Kafka client's own metrics -
 * {@code kafka.consumer.fetch.manager.records.lag} among them - to the registry. Nothing in this
 * platform bound it before, which is why a platform with an observability starter had no consumer lag
 * metric at all. Re-deriving lag here would mean computing a number the client already publishes, from
 * data the client has and this code does not.
 *
 * <h2>The signal worth alerting on is the one about a record that did not arrive</h2>
 *
 * <p>{@code ludwig.messaging.silent} is non-zero when nothing has been consumed on a topic for longer
 * than that topic's configured expectation. This is the same failure as file-ingest's file that never
 * arrived, and it presents the same way: a healthy pod, an empty log, and a projection quietly going
 * stale.
 *
 * <p>Absolute lag cannot see it, and that is the point. A topic with no traffic and a consumer whose
 * container died both read as lag zero - and so does a consumer that was never assigned a partition
 * because its group id was mistyped. Silence is the only one of the three that distinguishes "nothing to
 * do" from "nothing being done", because it is the only one that knows how long the absence is supposed
 * to be tolerable.
 */
public interface MessagingMetrics {

    /**
     * Records that a record was consumed, which is what arms the silence signal.
     *
     * @param topic the topic
     */
    void recordConsumed(String topic);

    /**
     * Records one retry attempt of a record.
     *
     * @param topic the topic
     */
    void recordRetry(String topic);

    /**
     * Records a record published to a dead-letter topic.
     *
     * <p>Alert on any non-zero value. A dead-lettered record is, by construction, work this platform
     * accepted and will not do.
     *
     * @param topic          the source topic
     * @param deadLetterTopic where it went
     * @param exception      the simple name of the failure's type - the type, never the message, which
     *                       routinely quotes the payload that caused it
     */
    void recordDeadLettered(String topic, String deadLetterTopic, String exception);

    /**
     * Records a record that exhausted its attempts and was <em>not</em> dead-lettered.
     *
     * <p>This should be zero at all times once a dead-letter topic exists, so a non-zero value does not
     * mean "some records failed" - it means the recoverer itself is failing, which is the case where the
     * platform believes it has a safety net and does not. The most likely cause is the one
     * {@code DeadLetterTopics} warns about: a dead-letter topic that was never provisioned.
     *
     * @param topic the source topic
     */
    void recordDropped(String topic);

    /**
     * Publishes the silence signal for one topic.
     *
     * <p>Registered once per topic with a supplier rather than pushed, and the difference is why this
     * module needs no thread of its own. A pushed gauge is only as fresh as whatever calls it, so it
     * needs a scheduler - and a scheduler that has to keep running for the gauge to stay truthful is one
     * more thing that can stop without saying so, which is the exact failure this gauge exists to
     * report. A supplier is evaluated by the metrics backend when it scrapes: if scraping stops, the
     * series stops, which is a visible absence rather than a stale zero.
     *
     * @param topic  the topic
     * @param silent evaluated on scrape, returning {@code 1} when nothing has been consumed within the
     *               configured window and {@code 0} otherwise
     */
    void registerSilence(String topic, java.util.function.IntSupplier silent);
}

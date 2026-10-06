package ru.ludwigandreas.pat.integration;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.pat.metrics.PatMetrics;

/**
 * The application and fixtures the exchange integration tests share.
 *
 * <p>One place, because several test classes need the same signing key, the same recording sink and the
 * same recording metrics - and three copies of a key generator is three chances for one of them to differ
 * in a way that makes a failure look like a product defect.
 *
 * <p><b>Not {@code @TestConfiguration}</b>, and that distinction cost a debugging session. A
 * {@code @TestConfiguration} class is registered <em>after</em> autoconfiguration has been evaluated, so
 * {@code @ConditionalOnMissingBean} in an autoconfiguration does not see its beans - which meant
 * {@code PatAutoConfiguration} installed its {@code NoopPatMetrics} fallback and every metric assertion in
 * this suite saw an empty list while the context started perfectly. A plain
 * {@code @SpringBootApplication} used through {@code classes =} registers them as ordinary user beans,
 * which is what the conditions are looking for.
 */
@SpringBootApplication
class ExchangeTestSupport {

    static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Bean
    RecordingSink recordingSink() {
        return new RecordingSink();
    }

    @Bean
    RecordingMetrics recordingMetrics() {
        return new RecordingMetrics();
    }

    @Bean
    Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /**
     * A generated signing key.
     *
     * <p>Generated per context rather than checked in, which is the right trade for a test: a committed
     * private key in a repository is a finding whatever its scope, and nothing here needs the key to be
     * stable across runs because every assertion is verified inside the same context that minted it.
     */
    @Bean
    RSAKey signingKey() throws Exception {
        return new RSAKeyGenerator(2048).keyID("pat-test").generate();
    }

    /** Collects audit events so a test can assert on the trail rather than on a mock. */
    static class RecordingSink implements AuditSink {

        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }

        List<String> actions() {
            return events.stream().map(AuditEvent::action).toList();
        }

        void clear() {
            events.clear();
        }

        /** The most recent event of an action, so an assertion is not order-dependent across a suite. */
        AuditEvent lastOf(String action) {
            return events.stream()
                    .filter(event -> event.action().equals(action))
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("no " + action + " event was recorded"));
        }
    }

    /**
     * Collects metric calls, which is how the uniform-failure tests show the reason is still available.
     *
     * <p>The pair of assertions that matters in this suite: the HTTP bodies are byte-for-byte identical
     * <em>and</em> the reasons are distinguishable here. Either one alone proves the wrong thing - identical
     * bodies with no telemetry is a defender with nothing, and distinguishable telemetry with varying
     * bodies is an oracle.
     */
    static class RecordingMetrics implements PatMetrics {

        private final List<String> failures = new ArrayList<>();
        private final List<String> exchanges = new ArrayList<>();
        private final List<String> rateLimited = new ArrayList<>();

        @Override
        public void recordExchange(String audience) {
            exchanges.add(audience);
        }

        @Override
        public void recordExchangeFailure(String reason) {
            failures.add(reason);
        }

        @Override
        public void recordRateLimited(String limit) {
            rateLimited.add(limit);
        }

        @Override
        public void recordLastUsedWriteFailed() {
            // not asserted in this suite
        }

        List<String> failures() {
            return List.copyOf(failures);
        }

        List<String> exchanges() {
            return List.copyOf(exchanges);
        }

        List<String> rateLimited() {
            return List.copyOf(rateLimited);
        }

        void clear() {
            failures.clear();
            exchanges.clear();
            rateLimited.clear();
        }
    }
}

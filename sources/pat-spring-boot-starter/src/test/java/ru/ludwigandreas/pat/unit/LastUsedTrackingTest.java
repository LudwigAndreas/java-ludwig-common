package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.pat.config.PatProperties;
import ru.ludwigandreas.pat.entity.PatEntity;
import ru.ludwigandreas.pat.metrics.NoopPatMetrics;
import ru.ludwigandreas.pat.repository.PatRepository;
import ru.ludwigandreas.pat.service.PatUsageTracker;

/**
 * The usage tracker: the debounce, the three signals, and the one failure that must not propagate.
 *
 * <p>The executor is same-thread throughout. Asynchrony is the production concern and a thread pool in a
 * unit test buys only flakiness - what is under test is <em>what</em> gets written and <em>when it is
 * skipped</em>, not that a pool runs it.
 */
class LastUsedTrackingTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final Map<UUID, PatEntity> stored = new HashMap<>();
    private final List<AuditEvent> audited = new ArrayList<>();

    private PatProperties properties;
    private PatEntity entity;

    /** Same-thread, so an assertion after the call sees the write. */
    private final Executor sameThread = Runnable::run;

    @BeforeEach
    void setUp() {
        properties = new PatProperties();
        entity = new PatEntity();
        entity.setId(ID);
        entity.setOwnerSubject("alice");
        entity.setName("ci");
        entity.setKeyId("key");
        entity.setSecretDigest("digest");
        entity.setScopes("orders:read");
        entity.setAudiences("deploy-service");
        stored.put(ID, entity);
    }

    private PatUsageTracker tracker() {
        return tracker(repositoryOver(stored));
    }

    private PatUsageTracker tracker(PatRepository repository) {
        return new PatUsageTracker(repository, properties, audited::add, new NoopPatMetrics(),
                Clock.fixed(NOW, ZoneOffset.UTC), sameThread);
    }

    private static PatRepository repositoryOver(Map<UUID, PatEntity> stored) {
        PatRepository repository = org.mockito.Mockito.mock(PatRepository.class);
        org.mockito.Mockito.when(repository.findById(org.mockito.ArgumentMatchers.any(UUID.class)))
                .thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(0))));
        org.mockito.Mockito.when(repository.save(org.mockito.ArgumentMatchers.any(PatEntity.class)))
                .thenAnswer(i -> {
                    PatEntity saved = i.getArgument(0);
                    stored.put(saved.getId(), saved);
                    return saved;
                });
        return repository;
    }

    private List<String> auditedActions() {
        return audited.stream().map(AuditEvent::action).toList();
    }

    @Test
    @DisplayName("a first use is audited once and writes the timestamp")
    void firstUseIsAudited() {
        tracker().recordVerifiedUse(entity, "10.0.0.1", "deploy-service");

        assertThat(auditedActions()).containsExactly("pat.first-use");
        assertThat(stored.get(ID).getLastUsedAt()).isEqualTo(NOW);
        assertThat(stored.get(ID).getLastUsedIp()).isEqualTo("10.0.0.1");
        assertThat(stored.get(ID).getUseCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an ordinary repeat use inside the debounce window writes nothing and audits nothing")
    void debounceSuppressesTheWrite() {
        entity.setLastUsedAt(NOW.minus(Duration.ofMinutes(1)));
        entity.setLastUsedIp("10.0.0.1");
        entity.setUseCount(41);

        tracker().recordVerifiedUse(entity, "10.0.0.1", "deploy-service");

        // The whole point: a token exchanged a thousand times in five minutes produces one write, not a
        // thousand, and no audit volume at all.
        assertThat(auditedActions()).isEmpty();
        assertThat(stored.get(ID).getLastUsedAt()).isEqualTo(NOW.minus(Duration.ofMinutes(1)));
        assertThat(stored.get(ID).getUseCount()).isEqualTo(41);
    }

    @Test
    @DisplayName("a repeat use past the debounce window writes, still without auditing")
    void debounceExpiryAllowsTheWrite() {
        entity.setLastUsedAt(NOW.minus(Duration.ofMinutes(10)));
        entity.setLastUsedIp("10.0.0.1");

        tracker().recordVerifiedUse(entity, "10.0.0.1", "deploy-service");

        assertThat(stored.get(ID).getLastUsedAt()).isEqualTo(NOW);
        assertThat(auditedActions()).isEmpty();
    }

    @Test
    @DisplayName("a changed source always writes, regardless of the debounce")
    void changedSourceBypassesTheDebounce() {
        entity.setLastUsedAt(NOW.minusSeconds(5));
        entity.setLastUsedIp("10.0.0.1");

        tracker().recordVerifiedUse(entity, "10.9.9.9", "deploy-service");

        // Otherwise the stored IP lags by up to the debounce interval, and the unseen-source signal
        // computed from it later compares against an address that is no longer the most recent - spurious
        // for a caller alternating between two addresses, missed for a sequence of three.
        assertThat(stored.get(ID).getLastUsedIp()).isEqualTo("10.9.9.9");
        assertThat(stored.get(ID).getLastUsedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a use from an unseen source is audited")
    void unseenSourceIsAudited() {
        entity.setLastUsedAt(NOW.minusSeconds(30));
        entity.setLastUsedIp("10.0.0.1");

        tracker().recordVerifiedUse(entity, "203.0.113.7", "deploy-service");

        assertThat(auditedActions()).containsExactly("pat.unseen-source");
        assertThat(audited.get(0).attributes())
                .containsEntry("sourceIp", "203.0.113.7")
                .containsEntry("previousIp", "10.0.0.1");
    }

    @Test
    @DisplayName("an unknown previous source is not an unseen source - a lost write is not a signal")
    void nullPreviousSourceIsNotASignal() {
        entity.setLastUsedAt(NOW.minusSeconds(30));
        entity.setLastUsedIp(null);

        tracker().recordVerifiedUse(entity, "10.0.0.1", "deploy-service");

        // Treating "we do not know" as "it changed" would turn every lost telemetry write into a security
        // signal, and a signal that fires on its own infrastructure's hiccups gets muted.
        assertThat(auditedActions()).isEmpty();
    }

    @Test
    @DisplayName("a token waking after a long silence is audited, with its previous use named")
    void dormantWakeIsAudited() {
        entity.setLastUsedAt(NOW.minus(Duration.ofDays(30)));
        entity.setLastUsedIp("10.0.0.1");

        tracker().recordVerifiedUse(entity, "10.0.0.1", "deploy-service");

        assertThat(auditedActions()).containsExactly("pat.dormant-wake");
        assertThat(audited.get(0).attributes())
                .containsEntry("previousUse", NOW.minus(Duration.ofDays(30)).toString());
    }

    @Test
    @DisplayName("a dormant wake from a new source produces both signals, because they mean different things")
    void dormantWakeFromNewSourceProducesBoth() {
        entity.setLastUsedAt(NOW.minus(Duration.ofDays(30)));
        entity.setLastUsedIp("10.0.0.1");

        tracker().recordVerifiedUse(entity, "203.0.113.7", "deploy-service");

        // Not collapsed into one event. "Quiet for a month" and "from somewhere new" are independently
        // interesting, and together they are the clearest shape of a credential found in an old repository.
        assertThat(auditedActions()).containsExactly("pat.dormant-wake", "pat.unseen-source");
    }

    @Test
    @DisplayName("the dormancy signal can be switched off without affecting inactivity expiry")
    void dormancySignalCanBeDisabled() {
        properties.setDormancyThreshold(Duration.ZERO);
        entity.setLastUsedAt(NOW.minus(Duration.ofDays(300)));
        entity.setLastUsedIp("10.0.0.1");

        tracker().recordVerifiedUse(entity, "10.0.0.1", "deploy-service");

        assertThat(auditedActions()).isEmpty();
    }

    @Test
    @DisplayName("a failed timestamp write is swallowed, so the exchange still succeeds")
    void writeFailureDoesNotPropagate() {
        PatRepository failing = org.mockito.Mockito.mock(PatRepository.class);
        org.mockito.Mockito.when(failing.findById(org.mockito.ArgumentMatchers.any(UUID.class)))
                .thenThrow(new IllegalStateException("connection closed"));

        // The assertion the task exists for. A telemetry write must not be able to break a production
        // pipeline - losing the row update costs an operator precision in a dormancy report, and that is
        // the cheaper of the two failures by a wide margin.
        assertThatCode(() -> tracker(failing).recordVerifiedUse(entity, "10.0.0.1", "deploy-service"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the swallowed failure is metered, so it is not a failure nobody learns about")
    void writeFailureIsMetered() {
        PatRepository failing = org.mockito.Mockito.mock(PatRepository.class);
        org.mockito.Mockito.when(failing.findById(org.mockito.ArgumentMatchers.any(UUID.class)))
                .thenThrow(new IllegalStateException("connection closed"));

        CountingMetrics metrics = new CountingMetrics();
        new PatUsageTracker(failing, properties, audited::add, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC), sameThread)
                .recordVerifiedUse(entity, "10.0.0.1", "deploy-service");

        assertThat(metrics.lastUsedWriteFailures).isEqualTo(1);
    }

    @Test
    @DisplayName("an audit sink failure is NOT swallowed, because that is AuditFailurePolicy's decision")
    void auditFailureIsNotSwallowed() {
        PatUsageTracker tracker = new PatUsageTracker(
                repositoryOver(stored), properties,
                event -> {
                    throw new IllegalStateException("sink unavailable");
                },
                new NoopPatMetrics(), Clock.fixed(NOW, ZoneOffset.UTC), sameThread);

        // The counterpart to writeFailureDoesNotPropagate, and the reason the two paths are split. Whether
        // a sink failure fails the caller is resolved from configuration by AuditFailurePolicy; a catch
        // here would override a decision the deployment made, which this repository forbids.
        assertThatThrownBy(() -> tracker.recordVerifiedUse(entity, "10.0.0.1", "deploy-service"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("sink unavailable");
    }

    /** Counts the one meter this test asserts on. */
    private static final class CountingMetrics extends NoopPatMetrics {

        private int lastUsedWriteFailures;

        @Override
        public void recordLastUsedWriteFailed() {
            lastUsedWriteFailures++;
        }
    }
}

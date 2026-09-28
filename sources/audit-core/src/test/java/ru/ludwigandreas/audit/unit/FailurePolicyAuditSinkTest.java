package ru.ludwigandreas.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditFailurePolicy;
import ru.ludwigandreas.audit.AuditFailurePolicyResolver;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.AuditSinkFailureListener;
import ru.ludwigandreas.audit.AuditWriteFailedException;
import ru.ludwigandreas.audit.FailurePolicyAuditSink;

/**
 * The decision that would otherwise be made by accident in a {@code catch} block.
 *
 * <p>Nine SPIs each chose "log and continue" and each wrote a paragraph defending it. For most of what they
 * audited that was right and it still is; for a state mutation it was not, and these tests are what makes
 * the difference deliberate rather than a property of whichever module wrote the sink.
 */
class FailurePolicyAuditSinkTest {

    private static final AuditSink THROWING = event -> {
        throw new IllegalStateException("sink down");
    };

    @Test
    @DisplayName("a throwing sink fails a setting.changed, because the change must not stand without its row")
    void failsAStateMutation() {
        AuditSink sink = new FailurePolicyAuditSink(THROWING,
                AuditFailurePolicyResolver.platformDefault(), AuditSinkFailureListener.noop());

        // AuditWriteFailedException and not the sink's own IllegalStateException: the caller has to be able
        // to tell "your change was rejected because it could not be recorded" from anything else, and this
        // is the type the shared problem pipeline answers as a 503.
        assertThatThrownBy(() -> sink.record(event(AuditCategories.SETTINGS, "setting.set")))
                .isInstanceOf(AuditWriteFailedException.class)
                .hasMessageContaining("settings/setting.set")
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a throwing sink does not fail an access.denied, because an audit outage is not a service outage")
    void doesNotFailAnObservationalEvent() {
        AuditSink sink = new FailurePolicyAuditSink(THROWING,
                AuditFailurePolicyResolver.platformDefault(), AuditSinkFailureListener.noop());

        assertThatCode(() -> sink.record(event(AuditCategories.ACCESS, "access.denied")))
                .doesNotThrowAnyException();
    }

    /**
     * The listener is how anybody finds out at all. Without it, a log-and-continue sink that has been
     * failing since a schema change is a warning line nobody reads, and the first person to notice is an
     * auditor asking why a month is missing.
     */
    @Test
    @DisplayName("every failure reaches the listener, whichever policy applies")
    void notifiesTheListenerUnderBothPolicies() {
        List<AuditFailurePolicy> seen = new ArrayList<>();
        AuditSinkFailureListener listener = (event, policy, cause) -> seen.add(policy);
        AuditSink sink = new FailurePolicyAuditSink(THROWING,
                AuditFailurePolicyResolver.platformDefault(), listener);

        sink.record(event(AuditCategories.ACCESS, "access.denied"));
        assertThatThrownBy(() -> sink.record(event(AuditCategories.SETTINGS, "setting.set")))
                .isInstanceOf(AuditWriteFailedException.class);

        assertThat(seen).containsExactly(
                AuditFailurePolicy.LOG_AND_CONTINUE, AuditFailurePolicy.FAIL_OPERATION);
    }

    @Test
    @DisplayName("a category the configuration does not name gets the configured fallback")
    void appliesTheFallbackToAnUnnamedCategory() {
        AuditSink sink = new FailurePolicyAuditSink(THROWING,
                AuditFailurePolicyResolver.ofCategories(
                        Map.of(AuditCategories.SETTINGS, AuditFailurePolicy.LOG_AND_CONTINUE),
                        AuditFailurePolicy.FAIL_OPERATION),
                AuditSinkFailureListener.noop());

        assertThatCode(() -> sink.record(event(AuditCategories.SETTINGS, "setting.set")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> sink.record(event(AuditCategories.EXPORT, "run.succeeded")))
                .isInstanceOf(AuditWriteFailedException.class);
    }

    @Test
    @DisplayName("a working sink is unaffected by the decorator")
    void passesThroughASuccessfulWrite() {
        List<AuditEvent> written = new ArrayList<>();
        AuditSink sink = new FailurePolicyAuditSink(written::add,
                AuditFailurePolicyResolver.platformDefault(), AuditSinkFailureListener.noop());

        AuditEvent event = event(AuditCategories.SETTINGS, "setting.set");
        sink.record(event);

        assertThat(written).containsExactly(event);
    }

    /** A null resolver must default to the platform policy, not to "never fail anything". */
    @Test
    void defaultsToThePlatformPolicyWhenGivenNone() {
        AuditSink sink = new FailurePolicyAuditSink(THROWING, null, null);

        assertThatThrownBy(() -> sink.record(event(AuditCategories.SETTINGS, "setting.set")))
                .isInstanceOf(AuditWriteFailedException.class);
    }

    private static AuditEvent event(String category, String action) {
        return AuditEvent.builder().category(category).action(action).build();
    }
}

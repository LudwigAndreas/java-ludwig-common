package ru.ludwigandreas.reconciliation.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.api.FeedRequest;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.MemberFeed;
import ru.ludwigandreas.reconciliation.api.MemberReconciler;
import ru.ludwigandreas.reconciliation.api.MemberResult;
import ru.ludwigandreas.reconciliation.api.PageResult;
import ru.ludwigandreas.reconciliation.api.ScopeSource;
import ru.ludwigandreas.reconciliation.config.EnumerationSettingsResolver;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.engine.enumeration.MemberPageApplier;
import ru.ludwigandreas.reconciliation.engine.enumeration.PageOutcome;
import ru.ludwigandreas.reconciliation.engine.enumeration.RegisteredEnumeration;
import ru.ludwigandreas.reconciliation.engine.enumeration.ScopeStateService;
import ru.ludwigandreas.reconciliation.engine.enumeration.ScopeWalker;
import ru.ludwigandreas.reconciliation.engine.enumeration.SweepService;
import ru.ludwigandreas.reconciliation.engine.enumeration.WalkReport;
import ru.ludwigandreas.reconciliation.entity.ScopeWalkKind;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.metrics.NoopReconciliationMetrics;
import ru.ludwigandreas.reconciliation.payload.PayloadCodec;
import ru.ludwigandreas.reconciliation.quota.RateLimitRegistry;
import ru.ludwigandreas.reconciliation.repository.SyncScopeMemberRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every call to a feed takes a permit from the enumeration's named rate limit first.
 *
 * <p>Driven through the real {@link RateLimitRegistry} with a budget of one call, so the second page
 * of a two-page scope is the call that cannot be made.
 */
class EnumerationRateLimitTest {

    private static final String LIMIT = "partner";

    private final AtomicInteger feedCalls = new AtomicInteger();
    private final ScopeStateService states = mock(ScopeStateService.class);
    private final MemberPageApplier applier = mock(MemberPageApplier.class);

    @Test
    void aFeedCallThatCannotGetAPermitIsNotMadeAndTheScopeBacksOff() {
        WalkReport report = walker().walk(enumeration(LIMIT), scope(), UUID.randomUUID(), deadline());

        assertThat(feedCalls.get()).isEqualTo(1);
        assertThat(report.status()).isEqualTo(WalkReport.Status.FAILED);
        assertThat(report.error()).contains("RateLimitExceededException");
        verify(states).fail(any(), anyString(), eq(enumeration(LIMIT).settings()));
    }

    @Test
    void anEnumerationWithNoRateLimitIsNotPaced() {
        WalkReport report = walker().walk(enumeration(null), scope(), UUID.randomUUID(), deadline());

        assertThat(feedCalls.get()).isEqualTo(2);
        assertThat(report.status()).isEqualTo(WalkReport.Status.COMPLETED);
    }

    private ScopeWalker walker() {
        when(states.begin(any(), any(), any()))
                .thenReturn(new ScopeStateService.WalkStart(ScopeWalkKind.INCREMENTAL, null, 0, null));
        when(applier.applyPage(any(), any(), anyList(), any())).thenReturn(PageOutcome.NONE);
        ReconciliationProperties.RateLimit oneCall = new ReconciliationProperties.RateLimit();
        oneCall.setPermits(1);
        oneCall.setPer(Duration.ofHours(1));
        oneCall.setTimeout(Duration.ZERO);
        return new ScopeWalker(states, applier, mock(SweepService.class), mock(SyncScopeMemberRepository.class),
                new PayloadCodec(new ObjectMapper()), new RateLimitRegistry(Map.of(LIMIT, oneCall)),
                new NoopReconciliationMetrics(), event -> { });
    }

    private static Instant deadline() {
        return Instant.now().plusSeconds(60);
    }

    private static SyncScopeState scope() {
        SyncScopeState state = new SyncScopeState();
        state.setTaskName("items");
        state.setScopeKey("all");
        return state;
    }

    private RegisteredEnumeration<String, String, String, String> enumeration(String rateLimit) {
        ReconciliationProperties.Enumeration configured = new ReconciliationProperties.Enumeration();
        configured.setRateLimit(rateLimit);
        return new RegisteredEnumeration<>(new TwoPages(), EnumerationSettingsResolver.resolve(
                "items", configured, new ReconciliationProperties.EnumerationDefaults()));
    }

    /** A feed with two pages, counting how often it is asked. */
    private final class TwoPages implements EnumerationTask<String, String, String, String> {

        @Override
        public String name() {
            return "items";
        }

        @Override
        public ScopeSource<String, String> scopes() {
            return ScopeSource.single("all", "all");
        }

        @Override
        public KeyCodec<String> scopeKeyCodec() {
            return KeyCodec.ofString();
        }

        @Override
        public MemberFeed<String, String> feed() {
            return (String scope, String cursor, FeedRequest request) -> {
                feedCalls.incrementAndGet();
                return cursor == null ? PageResult.of(List.of("a"), "1") : PageResult.last(List.of("b"));
            };
        }

        @Override
        public Function<String, String> memberKey() {
            return Function.identity();
        }

        @Override
        public KeyCodec<String> memberKeyCodec() {
            return KeyCodec.ofString();
        }

        @Override
        public MemberReconciler<String, String, String> reconciler() {
            return (scope, item, context) -> MemberResult.unchanged();
        }
    }
}

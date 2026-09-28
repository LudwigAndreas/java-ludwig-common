package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.ludwigandreas.security.unit.OrderFixture.ORDER;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.security.data.DataAccessGuard;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopePredicateFactory;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.exception.DataScopeMappingNotFoundException;
import ru.ludwigandreas.security.exception.PrincipalUnavailableException;
import ru.ludwigandreas.security.metrics.NoopSecurityMetrics;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.security.unit.OrderFixture.Order;

class DataAccessGuardTest {

    private static final AuditSink NO_AUDIT = event -> { };

    private DataScopeMapping<Order> orderMapping() {
        return DataScopeMapping.forResource("order", Order.class)
                .owner(ORDER.createdBy, Order::getCreatedBy)
                .build();
    }

    private DataAccessGuard guard(DataScopeProvider provider, Set<String> unscoped) {
        DataScopeRegistry registry = new DataScopeRegistry(List.of(orderMapping()), unscoped);
        return new DataAccessGuard(provider, registry, new DataScopePredicateFactory(), NO_AUDIT,
                new NoopSecurityMetrics(), false);
    }

    @BeforeEach
    void authenticate() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new LudwigAuthentication(LudwigPrincipal.builder()
                .subject("alice")
                .type(PrincipalType.USER)
                .build()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /**
     * {@code unscoped-resources} is the documented way to exempt reference data, and the guard has four
     * entry points. All of them have to honour it - an escape hatch that works through {@code check} and
     * throws through {@code predicate} is worse than none, because the inconsistency only shows up on
     * whichever endpoint happens to list rather than load.
     */
    @Test
    @DisplayName("every guard entry point honours unscoped-resources, including the query one")
    void unscopedResourcesAreUnrestrictedThroughEveryEntryPoint() {
        DataAccessGuard guard = guard((principal, resource, action) -> DataScope.none(),
                Set.of("reference-data"));

        assertThat(guard.scope("reference-data", DataAction.READ).isUnrestricted()).isTrue();
        assertThat(guard.permits("reference-data", DataAction.READ, new Order("someone"))).isTrue();
        assertThatCode(() -> guard.check("reference-data", DataAction.READ, new Order("someone")))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.predicate("reference-data", DataAction.READ))
                .doesNotThrowAnyException();
        assertThat(guard.predicate("reference-data", DataAction.READ).toString())
                .isEqualTo("true = true");
    }

    @Test
    @DisplayName("a resource that is neither mapped nor declared unscoped fails closed, loudly")
    void unknownResourceStillFailsClosed() {
        DataAccessGuard guard = guard((principal, resource, action) -> DataScope.all(), Set.of());

        assertThatThrownBy(() -> guard.predicate("widget", DataAction.READ))
                .isInstanceOf(DataScopeMappingNotFoundException.class);
    }

    @Test
    @DisplayName("a caller entitled to nothing gets an empty page, not an error that confirms rows exist")
    void deniedScopeCompilesToAnEmptyResultRatherThanAnException() {
        DataAccessGuard guard = guard((principal, resource, action) -> DataScope.none(), Set.of());

        assertThat(guard.predicate("order", DataAction.READ).toString()).isEqualTo("true = false");
    }

    @Test
    void checkRefusesAnObjectOutsideTheScopeAndAcceptsOneInside() {
        DataAccessGuard guard = guard((principal, resource, action) ->
                DataScope.restrictedTo(ru.ludwigandreas.security.data.ScopeDimension.OWNER,
                        principal.subject()), Set.of());

        assertThatCode(() -> guard.check("order", DataAction.READ, new Order("alice")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.check("order", DataAction.READ, new Order("bob")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageNotContaining("bob");
    }

    /**
     * Background threads have no caller. Failing loudly is deliberate: a silent fallback here would run
     * the query unscoped, and the bug would look like working code.
     */
    @Test
    void refusesToGuessWhenThereIsNoCaller() {
        SecurityContextHolder.clearContext();
        DataAccessGuard guard = guard((principal, resource, action) -> DataScope.all(), Set.of());

        assertThatThrownBy(() -> guard.predicate("order", DataAction.READ))
                .isInstanceOf(PrincipalUnavailableException.class);
    }
}

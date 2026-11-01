package ru.ludwigandreas.identity.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.identity.config.IdentityProjectionProperties;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.identity.feed.ApplyOutcome;
import ru.ludwigandreas.identity.feed.IdentityChange;
import ru.ludwigandreas.identity.feed.UserProfile;
import ru.ludwigandreas.identity.projection.IdentityChangeMapperImpl;
import ru.ludwigandreas.identity.projection.IdentityMetrics;
import ru.ludwigandreas.identity.projection.IdentityProjectionService;
import ru.ludwigandreas.identity.projection.IdentitySources;
import ru.ludwigandreas.identity.projection.SweepStore;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.PrincipalDisabledEvent;

@ExtendWith(MockitoExtension.class)
class IdentityProjectionServiceTest {

    private static final String SOURCE = "oidc";
    private static final String SUBJECT = "0b7c1b5e-user";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    @Mock
    private SecurityUserRepository repository;

    @Mock
    private LudwigCache<PrincipalRef, Authorities> authorityCache;

    /** Collects published events, so the disabling signal can be asserted rather than assumed. */
    private final List<Object> published = new ArrayList<>();

    private final ApplicationEventPublisher events = published::add;

    private final List<String> counted = new ArrayList<>();

    private final IdentityMetrics metrics = new IdentityMetrics() {
        @Override
        public void change(String source, String type, ApplyOutcome outcome) {
            counted.add(source + "/" + type + "/" + outcome.tag());
        }

        @Override
        public void sweep(String source, boolean aborted, long disabled) {
        }
    };

    private IdentityProjectionService service;

    @BeforeEach
    void setUp() {
        service = projection(true);
    }

    private IdentityProjectionService projection(boolean storeContact) {
        Map<String, IdentityProjectionProperties.Source> declared = new LinkedHashMap<>();
        declared.put(SOURCE, new IdentityProjectionProperties.Source());
        declared.put("customers", new IdentityProjectionProperties.Source());
        return new IdentityProjectionService(repository, new IdentityChangeMapperImpl(),
                new IdentitySources(declared), authorityCache, events, metrics, mock(SweepStore.class),
                storeContact);
    }

    private static IdentityChange.Upsert upsert(Instant occurredAt, Set<String> roles) {
        return new IdentityChange.Upsert(SOURCE, SUBJECT, occurredAt, "7", new UserProfile("Anna Schmidt", "tenant-a",
                roles, new UserProfile.Contact("anna@example.com", Boolean.TRUE, null, null, null, "@anna")));
    }

    private static SecurityUserEntity stored(Instant at, Set<String> roles) {
        SecurityUserEntity stored = new SecurityUserEntity();
        stored.setId(SUBJECT);
        stored.setSourceSystem(SOURCE);
        stored.setSourceTimestamp(at);
        stored.setTenantId("tenant-a");
        stored.getRoles().addAll(roles);
        return stored;
    }

    private SecurityUserEntity saved() {
        ArgumentCaptor<SecurityUserEntity> saved = ArgumentCaptor.forClass(SecurityUserEntity.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("a new user is inserted with the change's roles, source and provenance")
    void insertsUnknownUser() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.empty());

        assertThat(service.apply(upsert(NOW, Set.of("CATALOG_ADMIN")))).isEqualTo(ApplyOutcome.APPLIED);

        SecurityUserEntity user = saved();
        assertThat(user.getId()).isEqualTo(SUBJECT);
        assertThat(user.getRoles()).containsExactly("CATALOG_ADMIN");
        assertThat(user.getTenantId()).isEqualTo("tenant-a");
        assertThat(user.getSourceSystem()).isEqualTo(SOURCE);
        assertThat(user.getSourceTimestamp()).isEqualTo(NOW);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(authorityCache).evictAfterCommit(PrincipalRef.user(SUBJECT));
        assertThat(counted).containsExactly("oidc/upsert/applied");
    }

    @Test
    @DisplayName("the role set replaces the stored one, so a withdrawn role is gone")
    void replacesTheRoleSet() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.of(
                stored(NOW.minusSeconds(60), Set.of("ORDER_AGENT", "CATALOG_ADMIN"))));

        service.apply(upsert(NOW, Set.of("ORDER_AGENT")));

        assertThat(saved().getRoles()).containsExactly("ORDER_AGENT");
    }

    @Test
    @DisplayName("a profile field the change leaves unset keeps its stored value, roles included")
    void keepsUnsetFields() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.of(stored(NOW.minusSeconds(60), Set.of("ORDER_AGENT"))));

        service.apply(new IdentityChange.Upsert(SOURCE, SUBJECT, NOW, null,
                new UserProfile("Anna", null, null, null)));

        SecurityUserEntity user = saved();
        assertThat(user.getTenantId()).isEqualTo("tenant-a");
        assertThat(user.getRoles()).containsExactly("ORDER_AGENT");
        assertThat(user.getDisplayName()).isEqualTo("Anna");
    }

    @Test
    @DisplayName("a change older than the stored state is skipped as stale, so a replay cannot restore a revoked role")
    void skipsAnOlderChange() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.of(stored(NOW, Set.of())));

        assertThat(service.apply(upsert(NOW.minusSeconds(300), Set.of("CATALOG_ADMIN"))))
                .isEqualTo(ApplyOutcome.STALE);

        verify(repository, never()).save(any());
        assertThat(counted).containsExactly("oidc/upsert/stale");
    }

    @Test
    @DisplayName("re-applying the same change leaves the same state - feeds are at-least-once")
    void isIdempotentForTheSameTimestamp() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.of(stored(NOW, Set.of("CATALOG_ADMIN"))));

        assertThat(service.apply(upsert(NOW, Set.of("CATALOG_ADMIN")))).isEqualTo(ApplyOutcome.STALE);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a disable keeps the row, evicts the cached authorities and announces the principal")
    void disableKeepsRowEvictsAndAnnounces() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.of(stored(NOW.minusSeconds(60), Set.of("X"))));

        service.apply(new IdentityChange.Disable(SOURCE, SUBJECT, NOW, null));

        assertThat(saved().getStatus()).isEqualTo(UserStatus.DISABLED);
        verify(authorityCache).evictAfterCommit(PrincipalRef.user(SUBJECT));
        assertThat(published).singleElement().isEqualTo(new PrincipalDisabledEvent(SUBJECT, "disable"));
    }

    @Test
    @DisplayName("a remove is a status, never a deleted row, and is announced too")
    void removeIsAStatus() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.of(stored(NOW.minusSeconds(60), Set.of("X"))));

        service.apply(new IdentityChange.Remove(SOURCE, SUBJECT, NOW, null));

        assertThat(saved().getStatus()).isEqualTo(UserStatus.REMOVED);
        verify(repository, never()).delete(any());
        assertThat(published).singleElement().isEqualTo(new PrincipalDisabledEvent(SUBJECT, "remove"));
    }

    @Test
    @DisplayName("an upsert announces nothing")
    void upsertPublishesNothing() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.empty());

        service.apply(upsert(NOW, Set.of("X")));

        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("a change with no subject is discarded and counted")
    void discardsAChangeWithNoSubject() {
        assertThat(service.apply(new IdentityChange.Disable(SOURCE, " ", NOW, null)))
                .isEqualTo(ApplyOutcome.DISCARDED);

        verify(repository, never()).save(any());
        assertThat(counted).containsExactly("oidc/disable/discarded");
    }

    @Test
    @DisplayName("contact data is projected when the deployment opted into it")
    void projectsContactDataWhenEnabled() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.empty());

        service.apply(upsert(NOW, Set.of()));

        SecurityUserEntity user = saved();
        assertThat(user.getEmail()).isEqualTo("anna@example.com");
        assertThat(user.getEmailVerified()).isTrue();
        assertThat(user.getChatHandle()).isEqualTo("@anna");
    }

    @Test
    @DisplayName("contact data is scrubbed when the deployment did not opt in")
    void scrubsContactDataWhenDisabled() {
        SecurityUserEntity stored = stored(NOW.minusSeconds(60), Set.of());
        stored.setEmail("old@example.com");
        when(repository.findById(SUBJECT)).thenReturn(Optional.of(stored));

        projection(false).apply(upsert(NOW, Set.of()));

        SecurityUserEntity user = saved();
        assertThat(user.getEmail()).isNull();
        assertThat(user.getChatHandle()).isNull();
        assertThat(user.getEmailVerified()).isNull();
    }

    @Test
    @DisplayName("a change for a subject another source owns is refused and counted, never applied")
    void refusesAForeignSource() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.of(stored(NOW.minusSeconds(60), Set.of("X"))));

        assertThat(service.apply(new IdentityChange.Upsert("customers", SUBJECT, NOW, null,
                new UserProfile(null, null, Set.of("ADMIN"), null)))).isEqualTo(ApplyOutcome.FOREIGN_SOURCE);

        verify(repository, never()).save(any());
        assertThat(counted).containsExactly("customers/upsert/foreign-source");
    }

    @Test
    @DisplayName("a change from an undeclared source is a programming error, and a batch applies nothing")
    void refusesAnUndeclaredSource() {
        IdentityChange undeclared = new IdentityChange.Disable("staff", SUBJECT, NOW, null);

        assertThatThrownBy(() -> service.apply(undeclared)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("staff");
        assertThatThrownBy(() -> service.applyAll(List.of(upsert(NOW, Set.of()), undeclared)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a batch is applied in order, with one outcome per change")
    void appliesABatchInOrder() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.empty());

        assertThat(service.applyAll(List.of(upsert(NOW, Set.of("X")),
                new IdentityChange.Disable(SOURCE, "", NOW, null))))
                .containsExactly(ApplyOutcome.APPLIED, ApplyOutcome.DISCARDED);
    }
}

package ru.ludwigandreas.identity.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.identity.kafka.OidcUserEvent;
import ru.ludwigandreas.identity.kafka.OidcUserEventMapperImpl;
import ru.ludwigandreas.identity.kafka.OidcUserEventType;
import ru.ludwigandreas.identity.projection.IdentityProjectionService;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.authz.AuthorityCache;
import ru.ludwigandreas.security.authz.PrincipalRef;

@ExtendWith(MockitoExtension.class)
class IdentityProjectionServiceTest {

    private static final String SUBJECT = "0b7c1b5e-user";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    @Mock
    private SecurityUserRepository repository;

    @Mock
    private AuthorityCache authorityCache;

    private IdentityProjectionService service;

    @BeforeEach
    void setUp() {
        service = new IdentityProjectionService(
                repository, new OidcUserEventMapperImpl(), authorityCache, true);
    }

    private OidcUserEvent event(OidcUserEventType type, Instant occurredAt, Set<String> roles) {
        return new OidcUserEvent("evt-1", type, SUBJECT, "Anna Schmidt", "tenant-a", roles,
                "anna@example.com", null, null, "@anna", Boolean.TRUE, null, "7", occurredAt);
    }

    @Test
    @DisplayName("a new user is inserted with the event's roles and provenance")
    void insertsUnknownUser() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.empty());

        service.apply(event(OidcUserEventType.UPSERT, NOW, Set.of("CATALOG_ADMIN")));

        ArgumentCaptor<SecurityUserEntity> saved = ArgumentCaptor.forClass(SecurityUserEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(SUBJECT);
        assertThat(saved.getValue().getRoles()).containsExactly("CATALOG_ADMIN");
        assertThat(saved.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(saved.getValue().getSourceTimestamp()).isEqualTo(NOW);
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("an event older than the stored state is dropped, so a replay cannot restore a revoked role")
    void ignoresOutOfOrderEvent() {
        SecurityUserEntity stored = new SecurityUserEntity();
        stored.setId(SUBJECT);
        stored.setSourceTimestamp(NOW);
        stored.setRoles(new java.util.LinkedHashSet<>(Set.of("CATALOG_VIEWER")));
        when(repository.findById(SUBJECT)).thenReturn(Optional.of(stored));

        service.apply(event(OidcUserEventType.UPSERT, NOW.minusSeconds(60), Set.of("CATALOG_ADMIN")));

        verify(repository, never()).save(any());
        assertThat(stored.getRoles()).containsExactly("CATALOG_VIEWER");
    }

    @Test
    @DisplayName("re-applying the same event leaves the same state - the stream is at-least-once")
    void isIdempotentForTheSameTimestamp() {
        SecurityUserEntity stored = new SecurityUserEntity();
        stored.setId(SUBJECT);
        stored.setSourceTimestamp(NOW);
        when(repository.findById(SUBJECT)).thenReturn(Optional.of(stored));

        service.apply(event(OidcUserEventType.UPSERT, NOW, Set.of("CATALOG_ADMIN")));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a disable event keeps the row and evicts the cached authorities")
    void disableKeepsRowAndEvictsCache() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.empty());

        service.apply(event(OidcUserEventType.DISABLE, NOW, Set.of("CATALOG_ADMIN")));

        ArgumentCaptor<SecurityUserEntity> saved = ArgumentCaptor.forClass(SecurityUserEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.DISABLED);
        // No transaction is active in this unit test, so eviction happens inline rather than after commit.
        verify(authorityCache).evict(PrincipalRef.user(SUBJECT));
    }

    @Test
    void discardsAnEventWithNoSubject() {
        service.apply(new OidcUserEvent("evt-2", OidcUserEventType.UPSERT, "  ", null, null, Set.of(),
                null, null, null, null, null, null, null, NOW));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("contact data is projected when the deployment opted into it")
    void projectsContactDataWhenEnabled() {
        when(repository.findById(SUBJECT)).thenReturn(Optional.empty());

        service.apply(event(OidcUserEventType.UPSERT, NOW, Set.of()));

        ArgumentCaptor<SecurityUserEntity> saved = ArgumentCaptor.forClass(SecurityUserEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("anna@example.com");
        assertThat(saved.getValue().getEmailVerified()).isTrue();
        assertThat(saved.getValue().getChatHandle()).isEqualTo("@anna");
    }

    @Test
    @DisplayName("contact data is scrubbed when the deployment did not opt in")
    void scrubsContactDataWhenDisabled() {
        // Cleared on every apply, not only on insert: switching the flag off has to actually remove
        // what an earlier configuration stored, rather than freezing it in place forever.
        IdentityProjectionService withoutContact = new IdentityProjectionService(
                repository, new OidcUserEventMapperImpl(), authorityCache, false);
        when(repository.findById(SUBJECT)).thenReturn(Optional.empty());

        withoutContact.apply(event(OidcUserEventType.UPSERT, NOW, Set.of()));

        ArgumentCaptor<SecurityUserEntity> saved = ArgumentCaptor.forClass(SecurityUserEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isNull();
        assertThat(saved.getValue().getEmailVerified()).isNull();
        assertThat(saved.getValue().getChatHandle()).isNull();
    }
}

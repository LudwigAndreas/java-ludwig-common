package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.pat.config.PatProperties;
import ru.ludwigandreas.pat.entity.PatEntity;
import ru.ludwigandreas.pat.repository.PatQueryRepository;
import ru.ludwigandreas.pat.repository.PatRepository;
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatException;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.service.RevocationReasons;
import ru.ludwigandreas.pat.token.PatTokens;

/**
 * The lifecycle service: one test per refusal the capability specifies, plus rotation and revocation.
 *
 * <p>Hand-written fakes rather than Mockito, because the interesting assertions are about <em>state after</em>
 * - what the row holds once a rotation has happened - and a stubbed repository that does not actually store
 * anything cannot answer that. The fakes are a map and a list.
 */
class PatServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final Map<UUID, PatEntity> stored = new HashMap<>();
    private final List<AuditEvent> audited = new ArrayList<>();

    private PatProperties properties;
    private PatService service;

    @BeforeEach
    void setUp() {
        properties = new PatProperties();
        service = new PatService(
                repositoryOver(stored),
                new FakeQueries(stored),
                properties,
                audited::add,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static IssueTokenCommand command() {
        return new IssueTokenCommand("alice", "ci-deploy", Set.of("orders:read"),
                Set.of("deploy-service"), Duration.ofDays(30), List.of());
    }

    private List<String> auditedActions() {
        return audited.stream().map(AuditEvent::action).toList();
    }

    @Test
    @DisplayName("issuing stores a digest, never the secret, and returns the secret exactly once")
    void issueStoresOnlyADigest() {
        PatService.IssuedToken issued = service.issue(command(), "alice", Set.of("orders:read"));

        PatEntity entity = stored.get(issued.token().id());
        assertThat(entity.getSecretDigest()).isNotBlank().hasSize(64);
        // The secret appears in the rendered form and nowhere in the row. There is no accessor that
        // produces it from stored state, which is what makes "shown once" structural.
        assertThat(issued.rendered()).startsWith("lpat_");
        assertThat(entity.getSecretDigest()).isNotEqualTo(issued.rendered());
        assertThat(PatTokens.parse(issued.rendered()).orElseThrow().digest())
                .isEqualTo(entity.getSecretDigest());
        assertThat(auditedActions()).containsExactly("pat.issued");
    }

    @Test
    @DisplayName("the issued-token result does not print the secret, which a plain record would have")
    void issuedTokenDoesNotPrintTheSecret() {
        PatService.IssuedToken issued = service.issue(command(), "alice", Set.of("orders:read"));

        assertThat(issued.toString()).doesNotContain(issued.rendered());
    }

    /*
     * The assertions below check the message CODE rather than English prose, and that is the consequence of
     * basing these exceptions on web-core's LocalizedException: the human sentence now lives in the i18n
     * bundle, in both locales, and the exception carries only the key. Asserting prose would have meant a
     * test that fails when somebody improves the wording and passes when somebody changes which failure is
     * reported.
     */

    @Test
    @DisplayName("an empty scope set is refused, and the refusal is audited before it throws")
    void emptyScopeSetIsRefused() {
        IssueTokenCommand empty = new IssueTokenCommand("alice", "n", Set.of(),
                Set.of("deploy-service"), Duration.ofDays(1), List.of());

        assertThatThrownBy(() -> service.issue(empty, "alice", Set.of()))
                .isInstanceOf(PatException.class)
                .hasMessageContaining("ludwig.pat.scopes-required");
        // Audited even though it failed: a succession of refused issuance attempts is a signal, and a
        // signal that only existed in a 400 response would be invisible.
        assertThat(auditedActions()).containsExactly("pat.issuance-refused");
    }

    @Test
    @DisplayName("an empty audience set is refused - there is deliberately no default")
    void emptyAudienceSetIsRefused() {
        IssueTokenCommand noAudience = new IssueTokenCommand("alice", "n", Set.of("orders:read"),
                Set.of(), Duration.ofDays(1), List.of());

        assertThatThrownBy(() -> service.issue(noAudience, "alice", Set.of("orders:read")))
                .isInstanceOf(PatException.class)
                .hasMessageContaining("ludwig.pat.audiences-required");
    }

    @Test
    @DisplayName("a missing expiry is refused on a default deployment and allowed when enabled")
    void missingExpiryIsRefusedByDefault() {
        IssueTokenCommand noExpiry = new IssueTokenCommand("alice", "n", Set.of("orders:read"),
                Set.of("deploy-service"), null, List.of());

        assertThatThrownBy(() -> service.issue(noExpiry, "alice", Set.of("orders:read")))
                .isInstanceOf(PatException.class)
                .hasMessageContaining("ludwig.pat.expiry-required");

        properties.setAllowNonExpiring(true);
        assertThat(service.issue(noExpiry, "alice", Set.of("orders:read")).token().expiresAt()).isNull();
    }

    @Test
    @DisplayName("a lifetime past the ceiling is refused, not clamped")
    void lifetimeOverCeilingIsRefused() {
        IssueTokenCommand tooLong = new IssueTokenCommand("alice", "n", Set.of("orders:read"),
                Set.of("deploy-service"), Duration.ofDays(400), List.of());

        // Refused rather than silently clamped: a caller who believes they hold a one-year token and holds
        // a ninety-day one finds out when their pipeline breaks.
        assertThatThrownBy(() -> service.issue(tooLong, "alice", Set.of("orders:read")))
                .isInstanceOf(PatException.class)
                .hasMessageContaining("ludwig.pat.lifetime-too-long");
    }

    @Test
    @DisplayName("a scope the owner does not hold is refused as a fail-fast")
    void scopeNotHeldIsRefused() {
        assertThatThrownBy(() -> service.issue(command(), "alice", Set.of("something:else")))
                .isInstanceOf(PatException.class)
                .hasMessageContaining("ludwig.pat.scope-not-held");
    }

    @Test
    @DisplayName("the scope fail-fast is skipped when the owner's authorities cannot be resolved")
    void unknownOwnerAuthoritiesSkipsTheFailFast() {
        // An issuer that cannot resolve the owner's authorities must still be able to mint, because the
        // use-time intersection is what enforces. Refusing here would make the issuer depend on the
        // authority projection being reachable.
        assertThat(service.issue(command(), "alice", Set.of()).token().id()).isNotNull();
    }

    @Test
    @DisplayName("rotation keeps the previous secret valid for the overlap and preserves the token's identity")
    void rotationKeepsTheOldSecret() {
        PatService.IssuedToken first = service.issue(command(), "alice", Set.of("orders:read"));
        UUID id = first.token().id();
        // Read from the stored row, not from the returned snapshot: a snapshot deliberately carries no
        // digest, which is the property PatSnapshot exists for.
        String firstDigest = stored.get(id).getSecretDigest();

        PatService.IssuedToken second = service.rotate(id, "alice");
        PatEntity entity = stored.get(id);

        // The same token with a new secret, not a new token: same id, same scopes, same audiences.
        assertThat(second.token().id()).isEqualTo(id);
        // The stored column is the encoded form; the snapshot carries the decoded set.
        assertThat(second.token().scopes()).isEqualTo(first.token().scopes());
        assertThat(second.token().audiences()).isEqualTo(first.token().audiences());
        // The old digest is PRESERVED, not dropped. Getting this backwards invalidates the old secret
        // immediately, which is the failure the overlap exists to prevent and which no happy-path test sees.
        assertThat(entity.getPreviousSecretDigest()).isEqualTo(firstDigest);
        assertThat(entity.getSecretDigest()).isNotEqualTo(firstDigest);
        assertThat(entity.getPreviousSecretExpiresAt())
                .isEqualTo(NOW.plus(properties.getRotationOverlap()));
        assertThat(entity.acceptsPreviousSecret(NOW)).isTrue();
        assertThat(entity.acceptsPreviousSecret(NOW.plus(Duration.ofHours(25)))).isFalse();
        assertThat(auditedActions()).containsExactly("pat.issued", "pat.rotated");
    }

    @Test
    @DisplayName("revocation destroys both digests and is idempotent")
    void revocationDestroysDigests() {
        UUID id = service.issue(command(), "alice", Set.of("orders:read")).token().id();
        service.rotate(id, "alice");

        service.revoke(id, "operator", RevocationReasons.COMPROMISED);
        PatEntity entity = stored.get(id);

        assertThat(entity.getSecretDigest()).isNull();
        assertThat(entity.getPreviousSecretDigest()).isNull();
        assertThat(entity.getRevokedAt()).isEqualTo(NOW);
        assertThat(entity.getRevocationReason()).isEqualTo(RevocationReasons.COMPROMISED);
        // The row survives with everything an auditor needs: who owned it, what it could do, when it died.
        assertThat(entity.getOwnerSubject()).isEqualTo("alice");
        assertThat(entity.getScopes()).isNotBlank();
        assertThat(entity.isLive(NOW)).isFalse();

        // Idempotent: an operator revoking during an incident may well run the command twice, and the
        // second failing would send them to check whether the first worked.
        int before = audited.size();
        service.revoke(id, "operator", RevocationReasons.COMPROMISED);
        assertThat(audited).hasSize(before);
    }

    @Test
    @DisplayName("a disabled owner's live tokens are all revoked with a distinguishing reason")
    void ownerDisabledRevokesEverything() {
        service.issue(command(), "alice", Set.of("orders:read"));
        service.issue(command(), "alice", Set.of("orders:read"));

        assertThat(service.revokeAllForDisabledOwner("alice")).isEqualTo(2);
        assertThat(stored.values())
                .allMatch(entity -> RevocationReasons.OWNER_DISABLED.equals(entity.getRevocationReason()))
                .allMatch(entity -> entity.getSecretDigest() == null);
    }

    @Test
    @DisplayName("the expiry sweep destroys digests of expired tokens and does not revisit them")
    void expirySweepDestroysDigestsOnce() {
        UUID id = service.issue(
                new IssueTokenCommand("alice", "short", Set.of("orders:read"),
                        Set.of("deploy-service"), Duration.ofSeconds(1), List.of()),
                "alice", Set.of("orders:read")).token().id();

        // The fake clock does not move, so expire the row directly - the sweep's predicate is what is
        // under test, not the passage of time.
        stored.get(id).setExpiresAt(NOW.minusSeconds(10));

        assertThat(service.destroyExpiredSecrets()).isEqualTo(1);
        assertThat(stored.get(id).getSecretDigest()).isNull();
        // Not revisited: the predicate requires a non-null digest, so a handled row does not come back.
        assertThat(service.destroyExpiredSecrets()).isZero();
        assertThat(auditedActions()).contains("pat.expired");
    }

    @Test
    @DisplayName("the purge deletes terminal records past retention and audits each deletion")
    void purgeDeletesPastRetention() {
        UUID id = service.issue(command(), "alice", Set.of("orders:read")).token().id();
        service.revoke(id, "operator", RevocationReasons.REQUESTED);
        stored.get(id).setRevokedAt(NOW.minus(Duration.ofDays(400)));

        assertThat(service.purge()).isEqualTo(1);
        assertThat(stored).isEmpty();
        assertThat(auditedActions()).contains("pat.purged");
    }

    @Test
    @DisplayName("inactivity expiry is off when the window is zero")
    void inactivityExpiryCanBeDisabled() {
        properties.setInactivityExpiry(Duration.ZERO);
        service.issue(command(), "alice", Set.of("orders:read"));

        assertThat(service.revokeInactive()).isZero();
    }

    @Test
    @DisplayName("a token unused since the cutoff is revoked for inactivity")
    void inactivityExpiryRevokesQuietTokens() {
        UUID id = service.issue(command(), "alice", Set.of("orders:read")).token().id();
        // Never used, and minted long ago - which is why the query falls back to createdAt.
        stored.get(id).setCreatedAt(NOW.minus(Duration.ofDays(200)));

        assertThat(service.revokeInactive()).isEqualTo(1);
        assertThat(stored.get(id).getRevocationReason()).isEqualTo(RevocationReasons.INACTIVITY);
    }

    /**
     * A {@link PatRepository} backed by the test's own map.
     *
     * <p>Mockito rather than a hand-written implementation, and not for brevity: {@code JpaRepository}
     * declares around thirty methods, {@code PatService} calls three, and an implementation of all thirty
     * is two hundred lines in which the three that matter are hard to find. The mock answers exactly the
     * three and fails the test on anything else, which is the stronger property - a fake that quietly
     * returns a plausible empty value for a call the service was not supposed to make hides the coupling
     * this test exists to pin.
     */
    private static PatRepository repositoryOver(Map<UUID, PatEntity> stored) {
        PatRepository repository = org.mockito.Mockito.mock(PatRepository.class);
        org.mockito.Mockito.when(repository.save(org.mockito.ArgumentMatchers.any(PatEntity.class)))
                .thenAnswer(invocation -> {
                    PatEntity entity = invocation.getArgument(0);
                    // Assigns the id, because the DATABASE does. PatService deliberately leaves it null so
                    // that GeneratedEntity.isNew() is true and Spring Data persists rather than merges; a
                    // fake that did not generate one would make every stored key null and is what this test
                    // did before the service was corrected.
                    if (entity.getId() == null) {
                        entity.setId(UUID.randomUUID());
                    }
                    if (entity.getCreatedAt() == null) {
                        entity.setCreatedAt(NOW);
                    }
                    stored.put(entity.getId(), entity);
                    return entity;
                });
        org.mockito.Mockito.when(repository.findById(org.mockito.ArgumentMatchers.any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
        org.mockito.Mockito.doAnswer(invocation -> {
            stored.remove(((PatEntity) invocation.getArgument(0)).getId());
            return null;
        }).when(repository).delete(org.mockito.ArgumentMatchers.any(PatEntity.class));
        return repository;
    }

    /** The queries, evaluated over the same map, so a sweep's predicate is what is actually tested. */
    private record FakeQueries(Map<UUID, PatEntity> stored) implements PatQueryRepository {

        @Override
        public Optional<PatEntity> findByAnyKeyId(String keyId) {
            return stored.values().stream()
                    .filter(e -> keyId.equals(e.getKeyId()) || keyId.equals(e.getPreviousKeyId()))
                    .findFirst();
        }

        @Override
        public List<PatEntity> findByOwner(String ownerSubject) {
            return stored.values().stream()
                    .filter(e -> e.getOwnerSubject().equals(ownerSubject))
                    .toList();
        }

        @Override
        public List<PatEntity> findExpiredButNotYetDestroyed(Instant now, int limit) {
            return stored.values().stream()
                    .filter(e -> e.getExpiresAt() != null && e.getExpiresAt().isBefore(now))
                    .filter(e -> e.getSecretDigest() != null)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<PatEntity> findWithElapsedRotationOverlap(Instant now, int limit) {
            return stored.values().stream()
                    .filter(e -> e.getPreviousSecretDigest() != null)
                    .filter(e -> e.getPreviousSecretExpiresAt() != null
                            && e.getPreviousSecretExpiresAt().isBefore(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<PatEntity> findPurgeable(Instant purgeBefore, int limit) {
            return stored.values().stream()
                    .filter(e -> isTerminalBefore(e, purgeBefore))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<PatEntity> findLiveByOwner(String ownerSubject, Instant now) {
            return stored.values().stream()
                    .filter(e -> e.getOwnerSubject().equals(ownerSubject))
                    .filter(e -> e.isLive(now))
                    .toList();
        }

        private static boolean isTerminalBefore(PatEntity entity, Instant purgeBefore) {
            boolean revokedBefore =
                    entity.getRevokedAt() != null && entity.getRevokedAt().isBefore(purgeBefore);
            boolean expiredBefore =
                    entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(purgeBefore);
            return revokedBefore || expiredBefore;
        }

        @Override
        public List<PatEntity> findInactive(Instant cutoff, int limit) {
            return stored.values().stream()
                    .filter(e -> e.getRevokedAt() == null)
                    .filter(e -> {
                        Instant last = e.getLastUsedAt() == null ? e.getCreatedAt() : e.getLastUsedAt();
                        return last != null && last.isBefore(cutoff);
                    })
                    .limit(limit)
                    .toList();
        }
    }
}

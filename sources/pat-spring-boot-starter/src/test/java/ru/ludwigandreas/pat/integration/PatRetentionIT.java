package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.pat.entity.PatEntity;
import ru.ludwigandreas.pat.repository.PatRepository;
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.service.RevocationReasons;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * The retention lifecycle against a real database: digests destroyed, record kept, record eventually purged.
 *
 * <p>The sequence is the point and each step has a different reason:
 *
 * <ol>
 *   <li><b>Digests destroyed</b> at revocation or expiry, so the row stops being a credential. An expired
 *       token already fails verification on its expiry, so this is not what stops it working - it is so
 *       that a digest which <em>could</em> be matched if a future predicate were wrong is simply not
 *       there.</li>
 *   <li><b>Record kept</b>, because the question an investigation asks is "what could that credential
 *       do?", and it is asked long after the credential died.</li>
 *   <li><b>Record purged</b> past retention, and the purge is audited - so deleting the evidence is itself
 *       evidence.</li>
 * </ol>
 *
 * <p>Run against Postgres rather than with fakes because the assertion that matters is about what is
 * <em>in the row</em> after each step, and a fake repository would be asserting that the test's own map
 * behaves as the test expects.
 */
@SpringBootTest(classes = ExchangeTestSupport.class)
@Testcontainers
class PatRetentionIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(LudwigTestImages.POSTGRES);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private PatService service;

    @Autowired
    private PatRepository repository;

    @Autowired
    private ExchangeTestSupport.RecordingSink auditSink;

    private UUID issue(String name, Duration lifetime) {
        return service.issue(
                new IssueTokenCommand("alice", name, Set.of("orders:read"),
                        Set.of("deploy-service"), lifetime, List.of()),
                "alice", Set.of()).token().id();
    }

    @Test
    @DisplayName("revocation destroys the digests and keeps everything an auditor needs")
    void revocationDestroysDigestsAndKeepsTheRecord() {
        UUID id = issue("to-revoke", Duration.ofDays(30));
        service.rotate(id, "alice");

        service.revoke(id, "operator", RevocationReasons.COMPROMISED);

        PatEntity row = repository.findById(id).orElseThrow();
        assertThat(row.getSecretDigest()).isNull();
        assertThat(row.getPreviousSecretDigest()).isNull();
        // Everything that answers "what could this credential do, and whose was it" survives.
        assertThat(row.getOwnerSubject()).isEqualTo("alice");
        assertThat(row.getName()).isEqualTo("to-revoke");
        assertThat(row.getScopes()).contains("orders:read");
        assertThat(row.getAudiences()).contains("deploy-service");
        assertThat(row.getRevocationReason()).isEqualTo(RevocationReasons.COMPROMISED);
        assertThat(row.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("the expiry sweep destroys digests of expired tokens and audits each one")
    void expirySweepDestroysDigests() {
        // A negative lifetime, so the fixed clock sees it as already expired without the test mutating a
        // persisted row or moving time.
        UUID id = issue("already-expired", Duration.ofSeconds(-60));
        auditSink.clear();

        assertThat(service.destroyExpiredSecrets()).isGreaterThanOrEqualTo(1);

        assertThat(repository.findById(id).orElseThrow().getSecretDigest()).isNull();
        assertThat(auditSink.actions()).contains("pat.expired");
    }

    @Test
    @DisplayName("the expiry sweep does not revisit a row it has already handled")
    void expirySweepIsNotRepeated() {
        issue("already-expired-2", Duration.ofSeconds(-60));
        service.destroyExpiredSecrets();

        // The predicate requires a non-null digest, so a handled row drops out. Without that, every sweep
        // would re-audit every expired token ever issued - which turns an audit trail into noise
        // proportional to history rather than to events.
        assertThat(service.destroyExpiredSecrets()).isZero();
    }

    @Test
    @DisplayName("a live record is never purged, however old")
    void liveRecordSurvivesThePurge() {
        UUID id = issue("still-live", Duration.ofDays(30));

        assertThat(service.purge()).isZero();
        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("a terminal record past retention is purged, and the purge is audited")
    void terminalRecordPastRetentionIsPurgedAndAudited() {
        UUID id = issue("to-purge", Duration.ofDays(30));
        service.revoke(id, "operator", RevocationReasons.REQUESTED);

        // Backdate the revocation past the retention window. Done through the repository rather than by
        // shortening retention in configuration, so the test exercises the real default.
        PatEntity row = repository.findById(id).orElseThrow();
        row.setRevokedAt(ExchangeTestSupport.NOW.minus(Duration.ofDays(400)));
        repository.save(row);
        auditSink.clear();

        assertThat(service.purge()).isGreaterThanOrEqualTo(1);
        assertThat(repository.findById(id)).isEmpty();
        // Deleting the evidence is itself evidence.
        assertThat(auditSink.actions()).contains("pat.purged");
    }

    @Test
    @DisplayName("a rotation overlap that has elapsed drops the superseded digest")
    void elapsedRotationOverlapIsCleared() {
        UUID id = issue("to-rotate", Duration.ofDays(30));
        service.rotate(id, "alice");

        PatEntity rotated = repository.findById(id).orElseThrow();
        assertThat(rotated.getPreviousSecretDigest()).isNotNull();

        rotated.setPreviousSecretExpiresAt(ExchangeTestSupport.NOW.minusSeconds(60));
        repository.save(rotated);

        assertThat(service.endElapsedRotationOverlaps()).isGreaterThanOrEqualTo(1);

        PatEntity swept = repository.findById(id).orElseThrow();
        assertThat(swept.getPreviousSecretDigest()).isNull();
        assertThat(swept.getPreviousKeyId()).isNull();
        // The current secret is untouched: ending an overlap is not a revocation.
        assertThat(swept.getSecretDigest()).isNotNull();
    }
}

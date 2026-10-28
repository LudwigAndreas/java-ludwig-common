package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.pat.cache.PatCaches;
import ru.ludwigandreas.pat.entity.PatEntity;
import ru.ludwigandreas.pat.exchange.ExchangeFailure;
import ru.ludwigandreas.pat.exchange.ExchangeRefusedException;
import ru.ludwigandreas.pat.exchange.PatVerifier;
import ru.ludwigandreas.pat.repository.PatQueryRepository;
import ru.ludwigandreas.pat.repository.PatRepository;
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.service.RevocationReasons;
import ru.ludwigandreas.pat.token.PatTokens;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * A last-use write landing while a revoke or rotation is in flight, made deterministic.
 *
 * <p>The production race: a revoke reads the row, the asynchronous last-use write commits, the revoke saves. When
 * that write was a read-modify-save it bumped the entity version, so the revoke's save failed with an optimistic-lock
 * conflict - the operator saw a 500 and the token kept working. {@code PatDirectAuthenticationIT} hit it at random.
 *
 * <p>Here the interleaving is forced instead of waited for: an outer transaction loads the row into its persistence
 * context, the write runs to commit on another thread, and only then does the real {@code PatService.revoke} (or
 * {@code rotate}) run inside that same transaction, against the copy it loaded before the write.
 */
@SpringBootTest(classes = ExchangeTestSupport.class)
@Testcontainers
class PatUsageRaceIT {

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
    private PatQueryRepository queries;

    @Autowired
    private PatVerifier verifier;

    @Autowired
    private LudwigCacheRegistry caches;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private PatService.IssuedToken issue(String name) {
        return service.issue(
                new IssueTokenCommand("alice", name, Set.of("orders:read"), Set.of("deploy-service"),
                        Duration.ofDays(30), List.of()),
                "alice", Set.of());
    }

    /** Loads the row, lets a last-use write commit from another thread, then runs {@code action} on the stale copy. */
    private void afterConcurrentUse(UUID id, Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.findById(id).orElseThrow();
            CompletableFuture.runAsync(() -> queries.recordUse(id, ExchangeTestSupport.NOW, "10.0.0.7")).join();
            action.run();
        });
    }

    @Test
    @DisplayName("a revoke that read the row before a last-use write still succeeds, and stays revoked")
    void revokeSurvivesConcurrentUse() {
        PatService.IssuedToken issued = issue("race-revoke");
        UUID id = issued.token().id();

        afterConcurrentUse(id, () -> service.revoke(id, "operator", RevocationReasons.COMPROMISED));

        PatEntity row = repository.findById(id).orElseThrow();
        assertThat(row.getRevokedAt()).isNotNull();
        assertThat(row.getSecretDigest()).isNull();
        assertThat(row.getLastUsedAt()).isEqualTo(ExchangeTestSupport.NOW);
        assertThat(row.getUseCount()).isEqualTo(1);

        // Nothing cached answers for it, so this is the uncached exchange path - the one that answers 401.
        caches.cache(PatCaches.definition()).evict(PatTokens.parse(issued.rendered()).orElseThrow().digest());
        assertThatThrownBy(() -> verifier.verify(issued.rendered(), "deploy-service", "10.0.0.7"))
                .isInstanceOfSatisfying(ExchangeRefusedException.class,
                        refused -> assertThat(refused.failure()).isEqualTo(ExchangeFailure.REVOKED));
    }

    @Test
    @DisplayName("a rotation that read the row before a last-use write keeps its new key; only last use differs")
    void rotationSurvivesConcurrentUse() {
        UUID id = issue("race-rotate").token().id();
        String keyBefore = repository.findById(id).orElseThrow().getKeyId();

        afterConcurrentUse(id, () -> service.rotate(id, "alice"));

        PatEntity row = repository.findById(id).orElseThrow();
        assertThat(row.getKeyId()).isNotEqualTo(keyBefore);
        assertThat(row.getPreviousKeyId()).isEqualTo(keyBefore);
        assertThat(row.getRevokedAt()).isNull();
        assertThat(row.getLastUsedAt()).isEqualTo(ExchangeTestSupport.NOW);
    }

    @Test
    @DisplayName("a late last-use write never moves the recorded last use backwards")
    void lastUseIsForwardOnly() {
        UUID id = issue("forward-only").token().id();

        assertThat(queries.recordUse(id, ExchangeTestSupport.NOW, "10.0.0.1")).isEqualTo(1);
        assertThat(queries.recordUse(id, ExchangeTestSupport.NOW.minusSeconds(60), "10.0.0.2")).isZero();

        PatEntity row = repository.findById(id).orElseThrow();
        assertThat(row.getLastUsedAt()).isEqualTo(ExchangeTestSupport.NOW);
        assertThat(row.getLastUsedIp()).isEqualTo("10.0.0.1");
        assertThat(row.getUseCount()).isEqualTo(1);
    }
}

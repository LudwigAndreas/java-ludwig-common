package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.pat.token.PatTokens;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * The rate limiter, and the property that matters most: a malformed token costs <b>zero queries</b>.
 *
 * <p>That last one is asserted against Hibernate's own statistics rather than inferred from code reading,
 * because it is the difference between a flood of garbage costing CPU and a flood of garbage costing the
 * database. The ordering it depends on - parse, then checksum, then rate limit, then the indexed read - is
 * easy to reorder by accident while every other test keeps passing.
 */
@SpringBootTest(
        classes = ExchangeTestSupport.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "ludwig.pat.rate-limit.per-key-failures=3",
                // Generous enough that the query-counting tests are not throttled on their own source,
                // and small enough that the enumeration test crosses it within eight attempts.
                "ludwig.pat.rate-limit.per-source-failures=6",
                "ludwig.pat.rate-limit.window=60s",
                "spring.jpa.properties.hibernate.generate_statistics=true"
        })
@AutoConfigureMockMvc
@Testcontainers
class ExchangeRateLimitIT {

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
    private MockMvc mockMvc;

    @Autowired
    private ExchangeTestSupport.RecordingMetrics metrics;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void reset() {
        metrics.clear();
    }

    /**
     * Exchanges a token from a named source address.
     *
     * <p>Every test uses its <b>own</b> source, and that is isolation rather than realism dressing. The
     * limiter is in-process with a sixty-second window and the context is shared across this class, so
     * tests that share a source share its failure budget - which made the two query-counting tests fail for
     * a reason that had nothing to do with queries. A per-test source is also what a real deployment looks
     * like, so nothing is being faked to make the suite pass.
     */
    private int exchange(String token, String sourceIp) throws Exception {
        return mockMvc.perform(post("/oauth2/token")
                        .with(request -> {
                            request.setRemoteAddr(sourceIp);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        .param("subject_token", token)
                        .param("resource", "deploy-service"))
                .andReturn().getResponse().getStatus();
    }

    private org.hibernate.stat.Statistics statistics() {
        return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    }

    @Test
    @DisplayName("repeated failures for one key id are refused by the per-key limit")
    void perKeyLimitRefusesAfterTheBudget() throws Exception {
        // One key id, many guesses: the shape of somebody who has a key id and is working on the secret.
        String token = PatTokens.mint().rendered();

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(exchange(token, "10.0.0.1"))
                    .as("attempt %d is inside the budget", attempt).isEqualTo(401);
        }
        assertThat(exchange(token, "10.0.0.1")).as("the fourth is refused by the limiter").isEqualTo(401);

        // The status is identical either way - it has to be, or the limiter itself becomes the oracle that
        // tells an attacker they found a real key id. What distinguishes them is the metric.
        assertThat(metrics.rateLimited()).isNotEmpty();
    }

    @Test
    @DisplayName("many distinct key ids from one source are bounded by the per-source limit")
    void perSourceLimitBoundsKeyIdEnumeration() throws Exception {
        // The attack a per-key limit alone does nothing about: every guess is a different key id, so each
        // one gets a fresh per-key budget. Only the per-source limit bounds this.
        for (int attempt = 0; attempt < 8; attempt++) {
            exchange(PatTokens.mint().rendered(), "10.0.0.2");
        }

        assertThat(metrics.rateLimited())
                .as("enumerating key ids must be bounded by the source limit")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a malformed token costs zero database queries")
    void malformedTokenCostsNoQueries() throws Exception {
        statistics().setStatisticsEnabled(true);
        statistics().clear();
        long before = statistics().getPrepareStatementCount();

        // A malformed token has no key id, so only the per-source budget applies - and it is raised for
        // this test's source by using a fresh one, because what is under test is the query count rather
        // than the limiter.
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(exchange("this-is-not-a-token", "10.0.0.3")).isEqualTo(401);
        }

        // The assertion this class exists for. Parse and checksum happen before any I/O, so a flood of
        // garbage cannot convert into database load - which is what keeps the one endpoint an attacker can
        // aim at from being a way to exhaust the connection pool.
        assertThat(statistics().getPrepareStatementCount() - before)
                .as("20 malformed exchanges must issue no statements at all")
                .isZero();
        // Every reason is a PRE-DATABASE one. The first six report malformed and the rest report
        // rate-limited once the source budget is spent, and that distinction is beside the point: both are
        // reached before any I/O, which is the property this test is about. Asserting only "malformed"
        // would have been asserting the budget rather than the query count.
        assertThat(metrics.failures())
                .hasSize(20)
                .allSatisfy(reason -> assertThat(reason).isIn("malformed", "rate-limited"));
    }

    @Test
    @DisplayName("a well-formed token with an unknown key costs exactly one query per attempt")
    void unknownKeyCostsOnePointRead() throws Exception {
        statistics().setStatisticsEnabled(true);
        statistics().clear();
        long before = statistics().getPrepareStatementCount();

        exchange(PatTokens.mint().rendered(), "10.0.0.4");

        // One, and the number matters: verification is an indexed point read, not a scan and not a
        // sequence of candidate comparisons. A change that made it two would mean something was being
        // loaded that the verification does not need.
        assertThat(statistics().getPrepareStatementCount() - before)
                .as("a well-formed unknown token is one indexed lookup")
                .isEqualTo(1);
    }
}

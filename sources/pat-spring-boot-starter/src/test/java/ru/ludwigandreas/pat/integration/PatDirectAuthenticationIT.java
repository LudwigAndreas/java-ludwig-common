package ru.ludwigandreas.pat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.pat.cache.PatCaches;
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.token.PatTokens;
import ru.ludwigandreas.security.authn.pat.PatIntrospectionCaches;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * The whole direct-authentication loop, driven over real HTTP, in both of its configurations.
 *
 * <h2>Why this suite is not a MockMvc test</h2>
 *
 * <p>Because the thing being proved is that the loop closes. The filter in
 * {@code security-spring-boot-starter} makes an HTTP call to the introspection endpoint in this module,
 * which runs the verifier against the real table; a mock servlet environment has no server for that call
 * to reach, and substituting the introspection client would stub out precisely the part under test.
 *
 * <p>So the server runs on a real port and the filter is pointed at <em>itself</em>: one application that
 * both issues and consumes. That is not the production topology - the issuer is a separate service - but
 * it exercises every component in the path, and the component it would otherwise have to fake is the one
 * that was wrong twice.
 *
 * <p>Both of those defects were found here rather than in review, and neither was visible from either
 * half alone. The bearer-token filter answered {@code 401} before the PAT filter ran, because Spring
 * Security orders it ahead of {@code BasicAuthenticationFilter} and it authenticates unconditionally. And
 * the introspection endpoint sat behind {@code anyRequest().authenticated()} while the client that calls
 * it carries no credential of its own.
 */
@Testcontainers
class PatDirectAuthenticationIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(LudwigTestImages.POSTGRES);

    /**
     * A port chosen before the context starts, because the filter needs the issuer's URL as configuration.
     *
     * <p>{@code WebEnvironment.RANDOM_PORT} cannot be used for this: the port is not known until the
     * server is up, and by then the filter's client has already been constructed. Binding and releasing a
     * socket is the standard way round it, and the window between release and bind is the only race -
     * acceptable in a test, and the failure is a clean "address in use" rather than a wrong assertion.
     */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException("no free port for the test server", failure);
        }
    }

    private static final int ENABLED_PORT = freePort();

    private static final int DISABLED_PORT = freePort();

    private static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * The application: this module's starters, an authority resolver and one protected endpoint.
     *
     * <p>A resolver is required rather than incidental. The filter computes no authority - it resolves the
     * owner's live authorities through {@code AuthorityLookup} and intersects them with the token's scopes
     * - so without one every token would authenticate with nothing and the suite would prove the opposite
     * of what it claims while passing.
     */
    @SpringBootApplication
    static class DirectAuthenticationApp {

        /** Alice holds three roles and two permissions; no token is scoped to all of them. */
        @Bean
        AuthorityResolver authorityResolver() {
            return ref -> Authorities.builder()
                    .roles(Set.of("DEPLOYER", "ADMIN", "AUDITOR"))
                    .permissions(Set.of("deploy:write", "users:write"))
                    .build();
        }
    }

    /**
     * Something to be refused from, requiring one of the roles a token can be scoped to.
     *
     * <p>Picked up by the application's own component scan rather than declared as a {@code @Bean}. Both
     * would register it and the mapping would be ambiguous, which is the error this shape avoids.
     */
    @RestController
    static class ProtectedEndpoint {

        @GetMapping("/api/v1/deployments")
        @PreAuthorize("hasRole('DEPLOYER')")
        Map<String, Object> deployments() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/v1/users")
        @PreAuthorize("hasRole('ADMIN')")
        Map<String, Object> users() {
            return Map.of("ok", true);
        }
    }

    private static ResponseEntity<String> call(TestRestTemplate http, String path, String credential) {
        HttpHeaders headers = new HttpHeaders();
        if (credential != null) {
            headers.setBearerAuth(credential);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /**
     * With the filter on: the path this change exists to provide.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(
            classes = DirectAuthenticationApp.class,
            webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
            properties = {
                "ludwig.security.pat.filter.enabled=true",
                "ludwig.security.authorities.require-resolver=true",
            })
    class FilterEnabled {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            datasource(registry);
            registry.add("server.port", () -> ENABLED_PORT);
            registry.add("ludwig.security.pat.filter.issuer-base-url",
                    () -> "http://localhost:" + ENABLED_PORT);
        }

        @Autowired
        private TestRestTemplate http;

        @Autowired
        private PatService service;

        @Autowired
        private LudwigCacheRegistry caches;

        @Test
        @DisplayName("a token authenticates over plain HTTP, with the intersection and nothing more")
        void authenticatesWithTheIntersection() {
            String token = issue("intersection", Set.of("ROLE_DEPLOYER", "deploy:write")).rendered();

            assertThat(call(http, "/api/v1/deployments", token).getStatusCode())
                    .as("the token is scoped to DEPLOYER and alice holds it")
                    .isEqualTo(HttpStatus.OK);
            assertThat(call(http, "/api/v1/users", token).getStatusCode())
                    .as("alice holds ADMIN but this token is not scoped to it - the authority is the"
                            + " intersection, not the owner's")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a revoked token stops authenticating once no cache answers for it")
        void revokedTokenStopsWorking() {
            PatService.IssuedToken issued = issue("revocation", Set.of("ROLE_DEPLOYER"));
            String token = issued.rendered();
            assertThat(call(http, "/api/v1/deployments", token).getStatusCode()).isEqualTo(HttpStatus.OK);

            service.revoke(issued.token().id(), "operator", "incident");
            evictBothCaches(token);

            assertThat(call(http, "/api/v1/deployments", token).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an unparseable lpat_ credential is refused and costs no introspection call")
        void malformedCredentialIsRefused() {
            assertThat(call(http, "/api/v1/deployments", "lpat_notatoken").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a request with no credential is still refused, so the filter opened no door")
        void anonymousIsStillRefused() {
            assertThat(call(http, "/api/v1/deployments", null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        /**
         * Drops both cached answers, which is what the two TTLs do in production.
         *
         * <p>Evicted rather than waited out. Revocation deliberately does not evict - the issuer's
         * verification cache TTL <em>is</em> the revocation window, which is stated in {@code PatVerifier}
         * and bounded at startup by {@code RevocationWindowValidator} - so a test that asserted the
         * revocation took effect immediately would be asserting the opposite of the design. What this
         * asserts is the part the TTL does not cover: that once no cache answers, the revoked row is read
         * and the token is refused. The windows themselves are asserted in
         * {@code RevocationWindowValidationTest} and {@code PatIntrospectionCacheTest}.
         */
        private void evictBothCaches(String token) {
            String digest = PatTokens.parse(token).orElseThrow().digest();
            caches.cache(PatCaches.definition()).evict(digest);
            caches.cache(PatIntrospectionCaches.definition()).evict(digest);
        }

        private PatService.IssuedToken issue(String name, Set<String> scopes) {
            return service.issue(
                    new IssueTokenCommand("alice", name, scopes, Set.of("deploy-service"),
                            Duration.ofDays(30), List.of()),
                    "alice", Set.of());
        }
    }

    /**
     * With the filter off: the removal path, asserted rather than claimed.
     *
     * <p>This is the half that makes "scaffolding" a fact. When the company gateway gains PAT support the
     * property goes to {@code false}, and what must then be true is that an {@code lpat_} credential is
     * simply not a credential here - no residue, no half-enabled path - while the exchanged assertion that
     * route produces continues to authenticate. The assertion side is covered by
     * {@code PatAudienceScopingTest} and the converter's own suite, which is why this class asserts the
     * side those cannot: that turning the property off really does close the door.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(
            classes = DirectAuthenticationApp.class,
            webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
            properties = {
                "ludwig.security.authorities.require-resolver=true",
            })
    class FilterDisabled {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            datasource(registry);
            registry.add("server.port", () -> DISABLED_PORT);
        }

        @Autowired
        private TestRestTemplate http;

        @Autowired
        private PatService service;

        @Test
        @DisplayName("the same token is not a credential at all, and nothing else changes")
        void patIsNotACredentialWhenTheFilterIsOff() {
            String token = service.issue(
                    new IssueTokenCommand("alice", "ci", Set.of("ROLE_DEPLOYER"),
                            Set.of("deploy-service"), Duration.ofDays(30), List.of()),
                    "alice", Set.of()).rendered();

            assertThat(call(http, "/api/v1/deployments", token).getStatusCode())
                    .as("off by default is the whole point: adding the starter must not open a second"
                            + " authentication path")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("the introspection endpoint still answers, because the issuer's side is not the filter")
        void theIssuerSideIsUnaffected() {
            // Worth asserting: the two halves are configured independently, and a deployment that is the
            // ISSUER will have the endpoint on while its own filter is off. Conflating the two properties
            // would break exactly that deployment.
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
            ResponseEntity<String> response = http.exchange("/introspect", HttpMethod.POST,
                    new HttpEntity<>("token=lpat_notatoken", headers), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"active\":false");
        }
    }
}

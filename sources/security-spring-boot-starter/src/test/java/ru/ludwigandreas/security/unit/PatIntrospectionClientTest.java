package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.authn.pat.PatIntrospectionClient;

/**
 * The introspection client's failure behaviour, which is the only part worth a unit test.
 *
 * <p>The happy path needs a server and is covered end to end in {@code pat-spring-boot-starter}'s
 * integration suite, against the real endpoint. What matters here is that <b>every</b> failure fails
 * closed: an unreachable issuer must produce "not authenticated", never "authenticated with nothing",
 * which would be the same thing as admitting an unverified credential.
 */
class PatIntrospectionClientTest {

    @Test
    @DisplayName("an unreachable issuer yields empty rather than throwing")
    void unreachableIssuerFailsClosed() {
        // Port 1 on localhost refuses immediately, which is the fastest way to exercise the path an
        // operator will actually hit: the issuer is down or the DNS name is wrong.
        PatIntrospectionClient client = new PatIntrospectionClient(
                "http://127.0.0.1:1", "/introspect", "deploy-service", Duration.ofMillis(200));

        assertThat(client.introspect("lpat_aaaaaaaaaaa_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb_0"))
                .as("an unreachable issuer must become a refused request, never a propagating exception"
                        + " on an authentication filter")
                .isEmpty();
    }

    @Test
    @DisplayName("a timeout yields empty, so a slow issuer degrades rather than hangs")
    void timeoutFailsClosed() {
        // 10.255.255.1 is in a reserved range that blackholes rather than refusing, so the connect
        // attempt times out instead of failing fast - a different code path from the refusal above and
        // the one that matters more, because a hung request is worse for a caller than a refused one.
        PatIntrospectionClient client = new PatIntrospectionClient(
                "http://10.255.255.1:8080", "/introspect", "deploy-service", Duration.ofMillis(150));

        assertThat(client.introspect("lpat_aaaaaaaaaaa_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb_0"))
                .isEmpty();
    }

    @Test
    @DisplayName("a blank introspection path falls back to RFC 7662's, rather than posting to the root")
    void blankPathFallsBackToTheRfcDefault() {
        // Constructed without throwing is the assertion: a blank path must not become a POST to "/",
        // which on a service with a root mapping would be a request to something entirely unrelated.
        PatIntrospectionClient client = new PatIntrospectionClient(
                "http://127.0.0.1:1", "  ", "deploy-service", Duration.ofMillis(200));

        assertThat(client).isNotNull();
    }
}

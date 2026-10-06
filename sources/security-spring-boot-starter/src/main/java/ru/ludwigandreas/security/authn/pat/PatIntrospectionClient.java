package ru.ludwigandreas.security.authn.pat;

import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import ru.ludwigandreas.pat.introspection.PatIntrospection;
import ru.ludwigandreas.pat.introspection.PatIntrospectionResponse;

/**
 * Asks the issuing service whether a presented token is live, and whose it is.
 *
 * <h2>Why Spring's RestClient rather than this platform's own rest-client starter</h2>
 *
 * <p>{@code rest-client-spring-boot-starter} is the right choice for an outbound call from a service, and
 * the wrong one from here. This module sits nearest the bottom of the reactor - identity-projection,
 * user-settings, web-core, the PAT starter and every service depend on it - so anything added to its
 * classpath is added to every consumer of the platform. For one form-encoded POST, that is not a trade
 * worth making. {@code RestClient} arrives with {@code spring-boot-starter-web}, which this module already
 * has, so this class adds <b>no dependency at all</b>.
 *
 * <p>Checked rather than assumed: {@code git diff --exit-code} on this module's POM is part of the gate.
 *
 * <h2>Failing closed, and the two different ways to fail</h2>
 *
 * <p>Any failure yields {@link Optional#empty()}, which the filter treats as "not authenticated" - never
 * as "authenticated with nothing", which would be the same thing as admitting an unverified credential.
 *
 * <p>But the two causes are logged differently on purpose. An <em>inactive</em> answer is the issuer
 * working correctly and refusing a token, which is routine and logged at debug. An <em>unreachable</em>
 * issuer is this deployment being degraded, which is an operational event and logged at warn - because the
 * symptom an operator sees is identical ("my token stopped working") and the causes need opposite
 * responses: reissue the token, or fix the issuer.
 */
@Slf4j
public class PatIntrospectionClient {

    private final RestClient restClient;

    private final String path;

    private final String audience;

    /**
     * @param baseUrl  where the issuing service lives
     * @param path     the introspection path, defaulting to RFC 7662's
     * @param audience this service's own audience - sent as {@code resource}, because the question is
     *                 "may this token be used <em>here</em>" rather than "is this token real"
     * @param timeout  how long to wait. Short by default: this is on the request path, and a slow issuer
     *                 must degrade into a refused request rather than a hung one
     */
    public PatIntrospectionClient(String baseUrl, String path, String audience, Duration timeout) {
        this.path = path == null || path.isBlank() ? PatIntrospection.DEFAULT_PATH : path;
        this.audience = audience;
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Introspects a token.
     *
     * <p>Sends the token in a form body rather than a query parameter, as RFC 7662 requires and for the
     * reason it requires it: a query string is logged by proxies, access logs and browser history, and a
     * credential in any of those is a credential leaked.
     *
     * @return the response when the token is active, or empty for every failure - including an inactive
     *         answer, an unreachable issuer and a malformed reply
     */
    public Optional<PatIntrospectionResponse> introspect(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(PatIntrospection.TOKEN_PARAMETER, token);
        if (audience != null && !audience.isBlank()) {
            form.add("resource", audience);
        }
        try {
            PatIntrospectionResponse response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(PatIntrospectionResponse.class);
            if (response == null || !response.active()) {
                log.debug("Introspection reported a token inactive");
                return Optional.empty();
            }
            return Optional.of(response);
        } catch (RuntimeException failure) {
            // CHECKSTYLE.OFF: IllegalCatch - the issuer being unreachable must become a refused request,
            // never a propagating exception on an authentication filter. Enumerating what a client,
            // a DNS resolver, a socket and a deserializer might throw would be a list that falls behind
            // the libraries. Warn rather than debug: this is the deployment being degraded, not a token
            // being refused, and the two need opposite responses from whoever is paged.
            log.warn("Could not introspect a personal access token - the issuing service is unreachable"
                    + " or answered unusably, so the request will not be authenticated. Tokens stop"
                    + " working within the introspection cache TTL while this persists: {}",
                    failure.toString());
            return Optional.empty();
            // CHECKSTYLE.ON: IllegalCatch
        }
    }
}

package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import ru.ludwigandreas.security.authn.pat.PatAwareBearerTokenResolver;

/**
 * The resolver that makes the direct PAT path reachable at all.
 *
 * <p>Worth its own test rather than being left to the context test, because the context test proves the
 * resolver is <em>installed</em> and this proves it <em>discriminates</em>. Getting the second one wrong
 * in the direction of hiding too much would silently disable JWT authentication for the whole service.
 */
class PatAwareBearerTokenResolverTest {

    private final PatAwareBearerTokenResolver resolver =
            new PatAwareBearerTokenResolver(new DefaultBearerTokenResolver());

    @Test
    @DisplayName("an lpat_ credential resolves to no token, so the resource server passes the request on")
    void hidesPatCredentials() {
        assertThat(resolver.resolve(request("lpat_abcdefghijk_0123456789012345678901234567890123456_x")))
                .isNull();
    }

    @Test
    @DisplayName("an ordinary JWT resolves unchanged, which is the half that must not break")
    void passesJwtsThrough() {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.signature";

        assertThat(resolver.resolve(request(jwt))).isEqualTo(jwt);
    }

    @Test
    @DisplayName("a request with no Authorization header resolves to nothing, as the delegate decides")
    void noHeaderResolvesToNothing() {
        assertThat(resolver.resolve(new MockHttpServletRequest("GET", "/api/v1/things"))).isNull();
    }

    @Test
    @DisplayName("a token that merely contains lpat_ is not hidden - only the prefix counts")
    void onlyThePrefixCounts() {
        // An opaque token from some other issuer could contain the substring anywhere. Matching on
        // "contains" would hide it and break that issuer's tokens for reasons nobody could trace back to
        // this class.
        String token = "zzzlpat_abc";

        assertThat(resolver.resolve(request(token))).isEqualTo(token);
    }

    private static MockHttpServletRequest request(String credential) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/things");
        request.addHeader("Authorization", "Bearer " + credential);
        return request;
    }
}

package ru.ludwigandreas.restclient.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import ru.ludwigandreas.restclient.auth.MutableAuthRequest;
import ru.ludwigandreas.restclient.auth.provider.ApiKeyAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.BasicAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.BearerAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.CustomAuthenticationProvider;
import ru.ludwigandreas.restclient.auth.provider.NoneAuthenticationProvider;
import ru.ludwigandreas.restclient.config.AuthProperties;
import ru.ludwigandreas.restclient.spi.AuthRequest;
import ru.ludwigandreas.restclient.spi.ClientAuthenticator;
import ru.ludwigandreas.restclient.spi.TokenSupplier;

/** The shipped authentication schemes, including the ways each of them refuses to start. */
class AuthenticationProviderTest {

    @Test
    @DisplayName("none adds nothing")
    void noneAddsNothing() {
        AuthRequest request = request();
        new NoneAuthenticationProvider().create("billing", new AuthProperties()).authenticate(request);

        assertThat(request.headers()).isEmpty();
    }

    @Test
    @DisplayName("basic writes a base64 Authorization header and never names the password")
    void basicEncodesCredentials() {
        AuthProperties props = new AuthProperties();
        props.setUsername("alice");
        props.setPassword("hunter2");

        ClientAuthenticator authenticator = new BasicAuthenticationProvider().create("billing", props);
        AuthRequest request = request();
        authenticator.authenticate(request);

        assertThat(request.headers().getFirst("Authorization"))
                .isEqualTo("Basic YWxpY2U6aHVudGVyMg==");
        assertThat(authenticator.describe()).contains("alice").doesNotContain("hunter2");
    }

    @Test
    @DisplayName("basic without a username refuses to start")
    void basicRequiresAUsername() {
        assertThatThrownBy(() -> new BasicAuthenticationProvider().create("billing", new AuthProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth.username");
    }

    @Test
    @DisplayName("bearer with a static token writes the configured scheme")
    void bearerWritesStaticToken() {
        AuthProperties props = new AuthProperties();
        props.setToken("abc123");
        props.setScheme("Bearer");

        AuthRequest request = request();
        provider().create("billing", props).authenticate(request);

        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer abc123");
    }

    @Test
    @DisplayName("bearer with both a token and a supplier refuses rather than guessing")
    void bearerRefusesAmbiguousConfiguration() {
        AuthProperties props = new AuthProperties();
        props.setToken("abc123");
        props.setTokenSupplier("mySupplier");

        assertThatThrownBy(() -> provider().create("billing", props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("bearer with neither refuses too")
    void bearerRefusesEmptyConfiguration() {
        assertThatThrownBy(() -> provider().create("billing", new AuthProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("a supplier is told when the previous token was rejected")
    void bearerSupplierReceivesTheForceRefreshFlag() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        boolean[] forced = {false};
        beans.registerSingleton("mySupplier", (TokenSupplier) (client, force) -> {
            forced[0] = force;
            return "fresh";
        });
        AuthProperties props = new AuthProperties();
        props.setTokenSupplier("mySupplier");
        props.setScheme("Bearer");

        ClientAuthenticator authenticator =
                new BearerAuthenticationProvider(beans).create("billing", props);
        MutableAuthRequest rejected = new MutableAuthRequest("billing", HttpMethod.GET,
                URI.create("https://billing.internal/x"), new HttpHeaders(), 2, true);
        authenticator.authenticate(rejected);

        assertThat(forced[0]).isTrue();
        assertThat(rejected.headers().getFirst("Authorization")).isEqualTo("Bearer fresh");
        assertThat(authenticator.refreshOnUnauthorized()).isTrue();
    }

    @Test
    @DisplayName("a supplier that returns nothing fails the call rather than sending an empty header")
    void bearerSupplierMustProduceAToken() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("mySupplier", (TokenSupplier) (client, force) -> "  ");
        AuthProperties props = new AuthProperties();
        props.setTokenSupplier("mySupplier");

        ClientAuthenticator authenticator =
                new BearerAuthenticationProvider(beans).create("billing", props);

        assertThatThrownBy(() -> authenticator.authenticate(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned no token");
    }

    @Test
    @DisplayName("api-key writes the configured header with its prefix")
    void apiKeyWritesAHeader() {
        AuthProperties props = new AuthProperties();
        props.setKey("k-123");
        props.setHeaderName("X-Api-Key");
        props.setValuePrefix("Token ");

        AuthRequest request = request();
        new ApiKeyAuthenticationProvider().create("billing", props).authenticate(request);

        assertThat(request.headers().getFirst("X-Api-Key")).isEqualTo("Token k-123");
    }

    @Test
    @DisplayName("api-key in a query parameter rewrites the URI and sets no header")
    void apiKeyCanRewriteTheUri() {
        AuthProperties props = new AuthProperties();
        props.setKey("k-123");
        props.setQueryParamName("apikey");

        AuthRequest request = request();
        new ApiKeyAuthenticationProvider().create("billing", props).authenticate(request);

        assertThat(request.uri().toString()).contains("apikey=k-123");
        assertThat(request.headers()).isEmpty();
    }

    @Test
    @DisplayName("custom delegates to the named bean")
    void customDelegatesToABean() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("signer",
                (ClientAuthenticator) target -> target.headers().set("X-Signature", "signed"));
        AuthProperties props = new AuthProperties();
        props.setAuthenticator("signer");

        AuthRequest request = request();
        new CustomAuthenticationProvider(beans).create("billing", props).authenticate(request);

        assertThat(request.headers().getFirst("X-Signature")).isEqualTo("signed");
    }

    @Test
    @DisplayName("custom naming a bean that does not exist refuses to start")
    void customRequiresAnExistingBean() {
        AuthProperties props = new AuthProperties();
        props.setAuthenticator("nope");

        assertThatThrownBy(() ->
                new CustomAuthenticationProvider(new DefaultListableBeanFactory())
                        .create("billing", props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    private BearerAuthenticationProvider provider() {
        return new BearerAuthenticationProvider(new DefaultListableBeanFactory());
    }

    private MutableAuthRequest request() {
        return new MutableAuthRequest("billing", HttpMethod.GET,
                URI.create("https://billing.internal/invoices"), new HttpHeaders(), 1, false);
    }
}

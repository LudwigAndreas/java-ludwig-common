package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.ludwigandreas.security.authn.mtls.TrustedProxies;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

class TrustedProxiesTest {

    private final TrustedProxies trustedProxies = new TrustedProxies(List.of("10.4.0.0/16"));

    /** What Spring's ForwardedHeaderFilter installs: getRemoteAddr() answers from X-Forwarded-For. */
    private static final class ForwardedRequest extends HttpServletRequestWrapper {

        private final String forwardedFor;

        private ForwardedRequest(HttpServletRequest request, String forwardedFor) {
            super(request);
            this.forwardedFor = forwardedFor;
        }

        @Override
        public String getRemoteAddr() {
            return forwardedFor;
        }
    }

    @Test
    void matchesThePeerAgainstTheConfiguredRanges() {
        assertThat(trustedProxies.isTrustedAddress("10.4.1.7")).isTrue();
        assertThat(trustedProxies.isTrustedAddress("10.5.1.7")).isFalse();
        assertThat(trustedProxies.isTrustedAddress(null)).isFalse();
    }

    @Test
    @DisplayName("a client-supplied X-Forwarded-For cannot make an untrusted peer look trusted")
    void ignoresAForwardedAddressAndUsesTheRealPeer() {
        MockHttpServletRequest real = new MockHttpServletRequest();
        real.setRemoteAddr("203.0.113.9");                       // the attacker's actual address
        HttpServletRequest forwarded = new ForwardedRequest(real, "10.4.0.1");  // what they claim

        // Reading getRemoteAddr() off the wrapper would trust them; unwrapping does not.
        assertThat(forwarded.getRemoteAddr()).isEqualTo("10.4.0.1");
        assertThat(TrustedProxies.peerAddressOf(forwarded)).isEqualTo("203.0.113.9");
        assertThat(trustedProxies.isTrusted(forwarded)).isFalse();
    }

    @Test
    void stillTrustsTheRealProxyThroughTheSameWrapper() {
        MockHttpServletRequest real = new MockHttpServletRequest();
        real.setRemoteAddr("10.4.0.1");
        HttpServletRequest forwarded = new ForwardedRequest(real, "203.0.113.9");

        assertThat(trustedProxies.isTrusted(forwarded)).isTrue();
    }

    @Test
    void unwrapsSeveralLayers() {
        MockHttpServletRequest real = new MockHttpServletRequest();
        real.setRemoteAddr("10.4.0.1");
        HttpServletRequest wrapped = new ForwardedRequest(
                new ForwardedRequest(real, "198.51.100.1"), "203.0.113.9");

        assertThat(TrustedProxies.peerAddressOf(wrapped)).isEqualTo("10.4.0.1");
    }

    @Test
    @DisplayName("an empty or wide-open proxy list is a startup failure, not a warning")
    void refusesAMeaninglessConfiguration() {
        assertThatThrownBy(() -> new TrustedProxies(List.of()))
                .isInstanceOf(SecurityConfigurationException.class);
        assertThatThrownBy(() -> new TrustedProxies(List.of("0.0.0.0/0")))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("forgeable");
        assertThatThrownBy(() -> new TrustedProxies(List.of("::/0")))
                .isInstanceOf(SecurityConfigurationException.class);
    }
}

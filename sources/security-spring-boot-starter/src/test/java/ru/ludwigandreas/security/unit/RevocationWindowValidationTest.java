package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.config.RevocationWindowValidator;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * The revocation-window computation: that both paths are computed, and that each can fail startup alone.
 *
 * <p>The second half is the one worth having. A validator that computed the request/response window and
 * forgot the streaming one would pass every test written against the obvious case while logging a
 * correct-looking number that is false for exactly the callers whose window is largest.
 */
class RevocationWindowValidationTest {

    private static SecurityProperties.Pat pat(Duration assertion, Duration edge, Duration revalidation,
                                              Duration ceiling) {
        SecurityProperties.Pat pat = new SecurityProperties.Pat();
        pat.setAssertionLifetime(assertion);
        pat.setEdgeCacheLifetime(edge);
        pat.setRevalidationInterval(revalidation);
        pat.setMaxRevocationWindow(ceiling);
        return pat;
    }

    @Test
    @DisplayName("the defaults are within the default ceiling, so an unconfigured deployment starts")
    void defaultsStart() {
        assertThatCode(() -> new RevocationWindowValidator(
                new SecurityProperties.Pat(), Duration.ofSeconds(60)).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an over-ceiling request/response window fails startup naming every contributing value")
    void requestResponsePathFailsStartup() {
        SecurityProperties.Pat pat = pat(Duration.ofMinutes(10), Duration.ofMinutes(10),
                Duration.ofMinutes(1), Duration.ofMinutes(15));

        assertThatThrownBy(() -> new RevocationWindowValidator(pat, Duration.ofSeconds(60)).validate())
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("request/response")
                .hasMessageContaining("assertion-lifetime=PT10M")
                .hasMessageContaining("edge-cache-lifetime=PT10M")
                .hasMessageContaining("authority cache ttl=PT1M")
                .hasMessageContaining("max-revocation-window=PT15M");
    }

    @Test
    @DisplayName("an over-ceiling long-lived window fails startup on its own, with the other path compliant")
    void longLivedPathFailsStartupIndependently() {
        // The assertion that matters. The request/response sum here is 2m30s - comfortably inside the
        // ceiling - so a validator that only computed that path would start happily while a revoked token
        // kept working on an open stream for 31 minutes.
        SecurityProperties.Pat pat = pat(Duration.ofMinutes(1), Duration.ofMinutes(1),
                Duration.ofMinutes(30), Duration.ofMinutes(15));

        assertThatThrownBy(() -> new RevocationWindowValidator(pat, Duration.ofSeconds(30)).validate())
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("long-lived connection")
                .hasMessageContaining("revalidation-interval=PT30M");
    }

    @Test
    @DisplayName("the authority cache TTL is a term, so raising it alone can breach the ceiling")
    void authorityCacheTtlCounts() {
        SecurityProperties.Pat pat = pat(Duration.ofMinutes(5), Duration.ofMinutes(5),
                Duration.ofMinutes(1), Duration.ofMinutes(15));

        assertThatCode(() -> new RevocationWindowValidator(pat, Duration.ofMinutes(4)).validate())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new RevocationWindowValidator(pat, Duration.ofMinutes(6)).validate())
                .isInstanceOf(SecurityConfigurationException.class);
    }

    @Test
    @DisplayName("an absent cache module contributes zero rather than failing the computation")
    void absentCacheResolverContributesZero() {
        assertThatCode(() -> new RevocationWindowValidator(
                new SecurityProperties.Pat(), RevocationWindowValidator.authorityCacheTtl(null)).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validation can be turned off deliberately, and says so rather than going quiet")
    void validationCanBeDisabled() {
        SecurityProperties.Pat pat = pat(Duration.ofHours(10), Duration.ofHours(10),
                Duration.ofHours(10), Duration.ofMinutes(15));
        pat.setValidateRevocationWindow(false);

        assertThatCode(() -> new RevocationWindowValidator(pat, Duration.ofHours(1)).validate())
                .doesNotThrowAnyException();
    }
}

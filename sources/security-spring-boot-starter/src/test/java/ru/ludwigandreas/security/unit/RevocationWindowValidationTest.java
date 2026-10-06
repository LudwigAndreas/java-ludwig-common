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
 * The revocation-window computation: that all three paths are computed, and that each can fail startup
 * alone.
 *
 * <p>The second half is the one worth having. A validator that computed the request/response window and
 * forgot the streaming one would pass every test written against the obvious case while logging a
 * correct-looking number that is false for exactly the callers whose window is largest. The same applies
 * to the third path, which is why it is asserted the same way and not merely included in the log line.
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

    @Test
    @DisplayName("the direct-filter window fails startup on its own, with both other paths compliant")
    void directFilterPathFailsStartupIndependently() {
        // Same shape as the long-lived assertion above and for the same reason. The other two sums here
        // are 2m30s and 2m, well inside the ceiling, so a validator that computed only those would start
        // happily while a revoked token kept authenticating through the filter for 21 minutes.
        SecurityProperties.Pat pat = pat(Duration.ofMinutes(1), Duration.ofMinutes(1),
                Duration.ofMinutes(1), Duration.ofMinutes(15));

        assertThatThrownBy(() -> new RevocationWindowValidator(
                pat, Duration.ofMinutes(1), Duration.ofMinutes(20)).validate())
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("direct filter")
                .hasMessageContaining("introspection cache ttl=PT20M")
                .hasMessageContaining("authority cache ttl=PT1M")
                .hasMessageContaining("max-revocation-window=PT15M");
    }

    @Test
    @DisplayName("the third path is computed even when the filter is off, so enabling it later is not a surprise")
    void directFilterPathIsComputedWhenTheFilterIsOff() {
        // The filter being disabled is the common case and is indistinguishable here from an unconfigured
        // cache: both arrive as a zero TTL. The window is still computed and logged, so that a deployment
        // turning the filter on reads a number it has already seen rather than meeting it for the first
        // time at the moment it matters.
        assertThatCode(() -> new RevocationWindowValidator(
                new SecurityProperties.Pat(), Duration.ofSeconds(60), Duration.ZERO).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an absent cache module gives the third path a zero term rather than a failure")
    void absentCacheResolverContributesZeroToTheDirectPath() {
        assertThatCode(() -> new RevocationWindowValidator(
                new SecurityProperties.Pat(),
                RevocationWindowValidator.authorityCacheTtl(null),
                RevocationWindowValidator.introspectionCacheTtl(null)).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the two-term constructor still means 'no direct path', so the existing six assertions hold")
    void theTwoTermConstructorTreatsTheDirectPathAsAbsent() {
        // Asserted rather than assumed. The six assertions above all use the two-argument constructor and
        // were written before the third path existed; if that constructor had started contributing a
        // non-zero introspection term, they would have gone on passing while measuring something else.
        //
        // The terms are deliberately lopsided so the margin is three seconds: the request/response sum
        // lands exactly on the ceiling and the direct path two seconds under it. Any non-zero default for
        // the introspection term - a few seconds is the smallest plausible one - breaches it. A tidier
        // fixture with minutes of headroom would pass whatever that constructor did.
        SecurityProperties.Pat pat = pat(Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofMinutes(5));

        assertThatCode(() -> new RevocationWindowValidator(pat, Duration.ofSeconds(298)).validate())
                .doesNotThrowAnyException();
    }

}

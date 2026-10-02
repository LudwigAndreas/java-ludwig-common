package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.ludwigandreas.webcore.preference.RequestHeaderPreferenceSource;
import ru.ludwigandreas.webcore.preference.UserPreferenceSource;

/**
 * The request-header source: what it reads, and the much longer list of things it refuses to guess.
 */
class UserPreferenceSourceTest {

    private final RequestHeaderPreferenceSource source =
            new RequestHeaderPreferenceSource("X-Time-Zone", true);

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("off a request thread it abstains, because a scheduled job has no Accept-Language")
    void abstainsWithNoRequest() {
        assertThat(source.locale()).isEmpty();
        assertThat(source.zone()).isEmpty();
    }

    @Test
    @DisplayName("an absent Accept-Language abstains rather than answering the container's locale")
    void anAbsentAcceptLanguageAbstains() {
        bind(new MockHttpServletRequest());

        // The assertion that matters: HttpServletRequest.getLocale() answers the container's default
        // when the header is absent, and that default is the one answer this package must never
        // return. So the header's presence is checked explicitly rather than trusted to the parse.
        assertThat(source.locale()).isEmpty();
    }

    @Test
    @DisplayName("the quality-ordered first locale is taken from Accept-Language")
    void readsAcceptLanguage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5");
        bind(request);

        assertThat(source.locale()).contains(Locale.forLanguageTag("ru-RU"));
    }

    @Test
    @DisplayName("a region zone id is read from the configured header")
    void readsARegionZone() {
        bind(requestWithZone("Asia/Yekaterinburg"));

        assertThat(source.zone()).contains(ZoneId.of("Asia/Yekaterinburg"));
    }

    @Test
    @DisplayName("a fixed offset is accepted too, because that is what a browser can produce unaided")
    void readsAnOffsetZone() {
        bind(requestWithZone("+03:00"));

        assertThat(source.zone()).contains(ZoneOffset.ofHours(3));
    }

    @Test
    @DisplayName("an unusable zone is ignored, not rejected")
    void anUnusableZoneAbstains() {
        for (String value : new String[] {"Mars/Olympus_Mons", "", "   ", "not a zone"}) {
            bind(requestWithZone(value));
            assertThat(source.zone()).as("value '%s'", value).isEmpty();
        }
    }

    @Test
    @DisplayName("a blank header name means no timezone header is consulted at all")
    void noConfiguredHeaderMeansNoZone() {
        bind(requestWithZone("Asia/Yekaterinburg"));

        assertThat(new RequestHeaderPreferenceSource("  ", true).zone()).isEmpty();
    }

    @Test
    @DisplayName("Accept-Language can be switched off for a deployment that resolves locale from settings only")
    void acceptLanguageCanBeDisabled() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "ru");
        bind(request);

        assertThat(new RequestHeaderPreferenceSource("X-Time-Zone", false).locale()).isEmpty();
    }

    @Test
    @DisplayName("the source sits between the stored one and configuration")
    void orderIsTheRequestPosition() {
        assertThat(source.getOrder()).isEqualTo(UserPreferenceSource.REQUEST_ORDER);
        assertThat(UserPreferenceSource.STORED_ORDER).isLessThan(UserPreferenceSource.REQUEST_ORDER);
        assertThat(UserPreferenceSource.REQUEST_ORDER).isLessThan(UserPreferenceSource.CONFIGURED_ORDER);
    }

    private static MockHttpServletRequest requestWithZone(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Time-Zone", value);
        return request;
    }

    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}

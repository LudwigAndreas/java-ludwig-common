package ru.ludwigandreas.webcore.preference;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.web.servlet.LocaleContextResolver;

/**
 * The bean that makes {@code LocaleContextHolder} carry the caller's zone as well as their locale.
 *
 * <h2>What this replaces, and why replacing it is the whole point</h2>
 *
 * <p>{@code WebCoreLocalizationAutoConfiguration} used to register an
 * {@code AcceptHeaderLocaleResolver} under the name {@code localeResolver}. That class extends
 * {@code AbstractLocaleResolver} and declares no {@code resolveLocaleContext}: it is a plain
 * {@code LocaleResolver} and has no notion of a timezone. {@code DispatcherServlet} therefore
 * published a {@code SimpleLocaleContext}, {@code LocaleContextHolder.getTimeZone()} found no
 * {@code TimeZoneAwareLocaleContext}, and answered {@code TimeZone.getDefault()} - the container's
 * zone, which is UTC in the datacentre and the developer's own zone on a laptop. Every
 * {@code @JsonFormat} without an explicit zone, every {@code @DateTimeFormat} conversion and every
 * date argument interpolated into a message read that.
 *
 * <p>Taking the {@code localeResolver} name rather than adding a filter beside it is deliberate.
 * {@code DispatcherServlet} looks the bean up by name and, finding a {@code LocaleContextResolver},
 * publishes whatever context it returns - so every existing {@code LocaleContextHolder} caller in
 * the platform becomes correct with no code change anywhere. A filter beside the old resolver would
 * have left two answers to "what locale is this request", differing exactly when a stored
 * preference exists, which is the case that matters.
 *
 * <h2>Lazy</h2>
 *
 * <p>The returned context resolves on first use; see {@link UserPreferenceLocaleContext}. A dispatch
 * that formats nothing performs no settings read.
 *
 * <h2>Why setLocaleContext refuses</h2>
 *
 * <p>{@code AcceptHeaderLocaleResolver.setLocale} throws for the same reason and this follows it. A
 * preference is changed by writing the setting, through {@code user-settings-spring-boot-starter}'s
 * write path. A {@code LocaleChangeInterceptor} mutating the request's context instead would produce
 * a change that lasts exactly one request and then looks to the user as though it was silently
 * discarded - which is worse than refusing, because the user believes the setting took effect.
 */
public class UserPreferenceLocaleContextResolver implements LocaleContextResolver {

    private final UserPreferenceResolver resolver;

    /**
     * @param resolver the source chain
     */
    public UserPreferenceLocaleContextResolver(UserPreferenceResolver resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException("A locale context resolver needs a preference resolver");
        }
        this.resolver = resolver;
    }

    @Override
    public LocaleContext resolveLocaleContext(HttpServletRequest request) {
        return new UserPreferenceLocaleContext(resolver::resolve);
    }

    @Override
    public void setLocaleContext(HttpServletRequest request, HttpServletResponse response,
                                 LocaleContext localeContext) {
        throw new UnsupportedOperationException(
                "A caller's locale and zone cannot be changed through the request context. They are "
                        + "resolved from the caller's stored preferences, then the request headers, "
                        + "then configuration; to change one, write the setting (user.locale / "
                        + "user.timezone) through user-settings-spring-boot-starter.");
    }
}

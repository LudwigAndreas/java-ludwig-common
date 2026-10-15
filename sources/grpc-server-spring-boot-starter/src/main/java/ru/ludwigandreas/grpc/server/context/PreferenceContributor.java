package ru.ludwigandreas.grpc.server.context;

import io.grpc.Context;
import io.grpc.Metadata;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import ru.ludwigandreas.grpc.core.context.GrpcCallContext;
import ru.ludwigandreas.grpc.core.context.ThreadBinding;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.grpc.server.spi.GrpcCallContextContributor;
import ru.ludwigandreas.webcore.preference.UserPreferenceResolver;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * Reads the caller's locale and zone from the request metadata and binds the resolved
 * {@link UserPreferences} around every callback.
 *
 * <p>Resolution goes through web-core's {@link UserPreferenceResolver}, so a stored setting still
 * beats a header and the configured default still applies when the caller sent nothing. It runs
 * once per call, on the first callback: by then the principal is bound, which a stored-settings
 * source needs, and a later callback reuses the answer instead of asking the store again.
 */
public final class PreferenceContributor implements GrpcCallContextContributor {

    /**
     * The contributor order: after the principal and the correlation id.
     */
    public static final int ORDER = -100;

    private static final Context.Key<AtomicReference<UserPreferences>> RESOLVED =
            Context.key("ludwig-grpc-resolved-preferences");

    private final UserPreferenceResolver resolver;
    private final Metadata.Key<String> zoneKey;
    private final boolean acceptLanguage;

    /**
     * Creates the contributor.
     *
     * @param resolver       web-core's resolver
     * @param timeZoneHeader the HTTP header the zone travels in, or blank to ignore it
     * @param acceptLanguage whether the caller's language range is honoured
     */
    public PreferenceContributor(UserPreferenceResolver resolver, String timeZoneHeader, boolean acceptLanguage) {
        this.resolver = resolver;
        this.zoneKey = timeZoneHeader == null || timeZoneHeader.isBlank()
                ? null
                : GrpcMetadataKeys.forHeader(timeZoneHeader);
        this.acceptLanguage = acceptLanguage;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Context contribute(Context context, Metadata headers, String fullMethodName) {
        Context contributed = context.withValue(RESOLVED, new AtomicReference<>());
        Locale locale = acceptLanguage ? locale(headers.get(GrpcMetadataKeys.LOCALE)) : null;
        if (locale != null) {
            contributed = contributed.withValue(GrpcCallContext.REQUESTED_LOCALE, locale);
        }
        ZoneId zone = zoneKey == null ? null : zone(headers.get(zoneKey));
        if (zone != null) {
            contributed = contributed.withValue(GrpcCallContext.REQUESTED_ZONE, zone);
        }
        return contributed;
    }

    @Override
    public ThreadBinding binding() {
        return context -> {
            AtomicReference<UserPreferences> holder = RESOLVED.get(context);
            if (holder == null) {
                return ThreadBinding.Scope.NOOP;
            }
            UserPreferences preferences = holder.get();
            if (preferences == null) {
                preferences = resolver.resolve();
                holder.set(preferences);
            }
            return preferences.bind()::close;
        };
    }

    private static Locale locale(String languageRanges) {
        if (languageRanges == null || languageRanges.isBlank()) {
            return null;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(languageRanges);
            if (ranges.isEmpty() || "*".equals(ranges.get(0).getRange())) {
                return null;
            }
            Locale locale = Locale.forLanguageTag(ranges.get(0).getRange());
            return locale.getLanguage().isEmpty() ? null : locale;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static ZoneId zone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(value.trim(), ZoneId.SHORT_IDS);
        } catch (DateTimeException unusable) {
            return null;
        }
    }
}

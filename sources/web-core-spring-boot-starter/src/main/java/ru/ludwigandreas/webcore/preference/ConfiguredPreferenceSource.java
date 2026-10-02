package ru.ludwigandreas.webcore.preference;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

/**
 * The deployment's configured answer, and the reason {@link UserPreferenceResolver} always resolves.
 *
 * <p>Last in the order, and it must stay last: it is the only source that always answers, so any
 * source placed below it is unreachable.
 *
 * <p>It reads configuration and nothing else. In particular it does not call
 * {@code Locale.getDefault()} or {@code ZoneId.systemDefault()}, which is the behaviour this whole
 * package replaces and which {@code RuleGroup.PRESENTATION}'s {@code noAmbientDefaultLocaleOrZone}
 * rule fails the build over. An operator who wants the container's zone configures it by name, in a
 * file that can be reviewed, rather than inheriting it from however the image was built.
 */
public class ConfiguredPreferenceSource implements UserPreferenceSource {

    private final UserPreferences configured;

    /**
     * @param configured the deployment's defaults, from {@code ludwig.web.i18n.default-locale} and
     *                   {@code ludwig.web.preferences.default-zone}
     */
    public ConfiguredPreferenceSource(UserPreferences configured) {
        if (configured == null) {
            throw new IllegalArgumentException("The configured preference source needs its configuration");
        }
        this.configured = configured;
    }

    @Override
    public Optional<Locale> locale() {
        return Optional.of(configured.locale());
    }

    @Override
    public Optional<ZoneId> zone() {
        return Optional.of(configured.zone());
    }

    @Override
    public int getOrder() {
        return CONFIGURED_ORDER;
    }
}

package ru.ludwigandreas.notification.service.channel;

import java.util.Optional;
import ru.ludwigandreas.hotreload.binding.RefreshableConfig;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.settings.NotificationRuntimeProperties;
import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * The live answer to "is this channel on, and how fast may it go?".
 *
 * <p>Two sources, one of them live. The deployed defaults come from {@link NotificationProperties},
 * which is bound once at startup; the overrides come through the hot-reload module's
 * {@link RefreshableConfig}, which rebinds when a watched file or a Vault secret changes and keeps
 * serving the last valid value if the new one fails validation. So switching a channel off during an
 * incident is a config edit, and a malformed edit leaves the channel as it was rather than taking it
 * down a second way.
 *
 * <p>Read on every poll cycle rather than cached, because a cached value is a value that keeps being
 * used after somebody has changed it - which is the one thing this must never do.
 */
public class ChannelRuntime {

    private final NotificationProperties properties;
    private final RefreshableConfig<NotificationRuntimeProperties> overrides;

    public ChannelRuntime(NotificationProperties properties,
                          RefreshableConfig<NotificationRuntimeProperties> overrides) {
        this.properties = properties;
        this.overrides = overrides;
    }

    public boolean isEnabled(ChannelType channel) {
        return override(channel)
                .map(NotificationRuntimeProperties.ChannelOverride::getEnabled)
                .orElseGet(() -> settings(channel).isEnabled());
    }

    /** Cluster-wide sends permitted per window; {@code 0} means unlimited. */
    public int maxPerWindow(ChannelType channel) {
        return override(channel)
                .map(NotificationRuntimeProperties.ChannelOverride::getMaxPerWindow)
                .orElseGet(() -> settings(channel).getMaxPerWindow());
    }

    public int inAttemptRetries(ChannelType channel) {
        return settings(channel).getInAttemptRetries();
    }

    public java.time.Duration inAttemptRetryDelay(ChannelType channel) {
        return settings(channel).getInAttemptRetryDelay();
    }

    private Optional<NotificationRuntimeProperties.ChannelOverride> override(ChannelType channel) {
        NotificationRuntimeProperties current = overrides.get();
        if (current == null || current.getChannels() == null) {
            return Optional.empty();
        }
        // Looked up by name, because the properties package is deliberately free of service-layer
        // types - see NotificationRuntimeProperties#channels.
        return Optional.ofNullable(current.getChannels().get(channel.name()));
    }

    private NotificationProperties.ChannelSettings settings(ChannelType channel) {
        NotificationProperties.Channels channels = properties.getChannels();
        return switch (channel) {
            case EMAIL -> channels.getEmail();
            case CHAT -> channels.getChat();
            case WEBHOOK -> channels.getWebhook();
        };
    }
}

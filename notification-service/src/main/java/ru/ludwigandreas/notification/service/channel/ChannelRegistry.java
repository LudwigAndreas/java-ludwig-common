package ru.ludwigandreas.notification.service.channel;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.notification.service.exception.UnknownChannelException;
import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * Resolves a {@link ChannelType} to the bean that sends it.
 *
 * <p>Resolved once at construction into an {@link EnumMap} rather than by scanning the bean list on
 * every dispatch: the dispatch loop runs per delivery, and a linear scan with a
 * {@code supports(...)} call per candidate is work repeated thousands of times a minute to answer a
 * question whose answer cannot change.
 *
 * <p>An ambiguous registration - two beans both claiming one channel - fails at construction. The
 * alternative is picking one by bean-definition order, which means a channel silently changes
 * behaviour when an unrelated dependency reorders the context.
 */
public class ChannelRegistry {

    private final Map<ChannelType, NotificationChannel> byType = new EnumMap<>(ChannelType.class);

    public ChannelRegistry(List<NotificationChannel> channels) {
        for (ChannelType type : ChannelType.values()) {
            for (NotificationChannel channel : channels) {
                if (!channel.supports(type)) {
                    continue;
                }
                NotificationChannel existing = byType.putIfAbsent(type, channel);
                if (existing != null && existing != channel) {
                    throw new IllegalStateException("Two channels claim " + type + ": "
                            + existing.name() + " and " + channel.name());
                }
            }
        }
    }

    public Optional<NotificationChannel> find(ChannelType type) {
        return Optional.ofNullable(byType.get(type));
    }

    /** @throws UnknownChannelException when no bean claims this transport */
    public NotificationChannel require(ChannelType type) {
        return find(type).orElseThrow(() -> new UnknownChannelException(String.valueOf(type)));
    }

    /** Transports that actually have an implementation behind them in this deployment. */
    public java.util.Set<ChannelType> registered() {
        return java.util.Set.copyOf(byType.keySet());
    }
}

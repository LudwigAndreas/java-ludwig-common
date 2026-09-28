package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.service.channel.ChannelRegistry;
import ru.ludwigandreas.notification.service.channel.NotificationChannel;
import ru.ludwigandreas.notification.service.exception.UnknownChannelException;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.DeliveryResult;
import ru.ludwigandreas.notification.service.model.RenderedNotification;

/** Adding a channel is adding a bean; the registry is what makes that true. */
class ChannelRegistryTest {

    @Test
    @DisplayName("a channel is resolved by the transport it claims")
    void resolvesByClaim() {
        ChannelRegistry registry = new ChannelRegistry(List.of(channel("email", ChannelType.EMAIL)));

        assertThat(registry.find(ChannelType.EMAIL)).isPresent();
        assertThat(registry.find(ChannelType.CHAT)).isEmpty();
        assertThat(registry.registered()).containsExactly(ChannelType.EMAIL);
    }

    @Test
    @DisplayName("one bean may claim several transports")
    void oneBeanMayServeSeveralTransports() {
        ChannelRegistry registry = new ChannelRegistry(
                List.of(channel("http", ChannelType.CHAT, ChannelType.WEBHOOK)));

        assertThat(registry.registered()).containsExactlyInAnyOrder(ChannelType.CHAT, ChannelType.WEBHOOK);
    }

    /**
     * The alternative is picking one by bean-definition order, which means a channel silently changes
     * behaviour when an unrelated dependency reorders the context.
     */
    @Test
    @DisplayName("two beans claiming one transport is a startup failure, not a coin toss")
    void ambiguityFailsAtStartup() {
        assertThatThrownBy(() -> new ChannelRegistry(List.of(
                channel("first", ChannelType.EMAIL),
                channel("second", ChannelType.EMAIL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first")
                .hasMessageContaining("second");
    }

    @Test
    @DisplayName("requiring an unregistered transport is a localized 500, not a null")
    void requireThrowsForUnregistered() {
        ChannelRegistry registry = new ChannelRegistry(List.of());

        assertThatThrownBy(() -> registry.require(ChannelType.EMAIL))
                .isInstanceOf(UnknownChannelException.class);
    }

    private static NotificationChannel channel(String name, ChannelType... claims) {
        List<ChannelType> supported = List.of(claims);
        return new NotificationChannel() {
            @Override
            public boolean supports(ChannelType channelType) {
                return supported.contains(channelType);
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public DeliveryResult send(RenderedNotification notification) {
                return DeliveryResult.sent("test");
            }
        };
    }
}

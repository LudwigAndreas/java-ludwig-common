package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.service.model.ChannelClass;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;

/**
 * The channel vocabulary: that every transport is classified, and that the three representations of
 * it line up.
 *
 * <p>Most of what matters here is enforced by the compiler rather than by these tests, and that is
 * the design: {@link ChannelClass} is a mandatory constructor argument of {@link ChannelType}, so a
 * transport added without a classification does not compile, and MapStruct bridges the three enums
 * by constant name, so a transport present in one and absent from another fails the build. Adding
 * {@code IN_APP} to {@link ChannelKind} alone was tried while implementing this change and produced
 * exactly that failure - <em>"The following constants from the source enum have no corresponding
 * constant in the target enum ...: IN_APP"</em> - which is the evidence the check is real rather
 * than assumed.
 *
 * <p>What these tests add is the part the compiler cannot see: that the <em>names</em> match across
 * the three enums rather than merely the counts, and that the classification is actually used to
 * split the transports rather than declared and ignored.
 */
class ChannelClassificationTest {

    @ParameterizedTest
    @EnumSource(ChannelType.class)
    @DisplayName("every transport declares a classification")
    void everyTransportIsClassified(ChannelType channel) {
        assertThat(channel.channelClass()).isNotNull();
    }

    /**
     * One passive transport today. This is asserted not to freeze the number but to make a second
     * one a deliberate act: a new passive transport switches off quiet hours, digest collapsing and
     * the address suppression list for itself, and that should be read and confirmed rather than
     * inherited by adding a constant.
     */
    @Test
    @DisplayName("the inbox is the only passive transport")
    void inboxIsTheOnlyPassiveTransport() {
        assertThat(Arrays.stream(ChannelType.values()).filter(ChannelType::isPassive))
                .containsExactly(ChannelType.IN_APP);
    }

    @Test
    @DisplayName("every pushing transport is interrupting")
    void pushingTransportsInterrupt() {
        assertThat(ChannelType.EMAIL.channelClass()).isEqualTo(ChannelClass.INTERRUPTING);
        assertThat(ChannelType.CHAT.channelClass()).isEqualTo(ChannelClass.INTERRUPTING);
        assertThat(ChannelType.WEBHOOK.channelClass()).isEqualTo(ChannelClass.INTERRUPTING);
        assertThat(ChannelType.EMAIL.isPassive()).isFalse();
    }

    /**
     * The compiler proves the mapper can map every constant; it does not prove the three enums spell
     * the transports the same way, because MapStruct would be equally happy with a hand-written
     * mapping between differently named constants. Name equality is what makes the stored value, the
     * wire value and the service value the same word in a log line, a column and a payload.
     */
    @Test
    @DisplayName("the three representations of the vocabulary have identical constant names")
    void vocabulariesAgreeByName() {
        assertThat(names(ChannelKind.values())).isEqualTo(names(ChannelType.values()));
        assertThat(names(ChannelTypeDto.values())).isEqualTo(names(ChannelType.values()));
    }

    private static String[] names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).sorted().toArray(String[]::new);
    }
}

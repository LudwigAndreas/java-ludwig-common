package ru.ludwigandreas.notification.local;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.ludwigandreas.notification.web.dto.CategoryClassDto;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.PriorityDto;
import ru.ludwigandreas.notification.web.dto.RecipientDto;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixture;

/**
 * Two notifications, submitted through the service's own API as the administrator.
 *
 * <p>One goes to Ada over every channel she has, and is delivered by the queue poller within a few
 * seconds of start-up - so her inbox, the mail server and the chat stand-in all have something in
 * them. The other is scheduled for tomorrow and therefore stays queued, which gives the delivery
 * administration endpoints a pending row to show.
 */
public class NotificationSeed implements SeedFixture {

    private static final String SEND_PATH = "/api/v1/notifications";

    private final LocalApi api;

    public NotificationSeed(LocalApi api) {
        this.api = api;
    }

    @Override
    public void seed() {
        api.post(LocalCaller.ADMIN, SEND_PATH, new SendNotificationRequest(
                "welcome", "account", CategoryClassDto.TRANSACTIONAL, PriorityDto.NORMAL,
                Set.of(ChannelTypeDto.IN_APP, ChannelTypeDto.EMAIL, ChannelTypeDto.CHAT),
                List.of(new RecipientDto(LocalCaller.ADA.subject(), null, "en", null, Map.of())),
                Map.of("productName", "Ludwig"), null));
        api.post(LocalCaller.ADMIN, SEND_PATH, new SendNotificationRequest(
                "password-reset", "security", CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH,
                Set.of(ChannelTypeDto.EMAIL),
                List.of(new RecipientDto(RecipientSeed.EMAIL_ONLY, null, null, null, null)),
                Map.of("resetLink", "https://reset.example/abc", "expiresInMinutes", 15),
                Instant.now().plus(Duration.ofDays(1))));
    }

    @Override
    public List<Class<? extends SeedFixture>> after() {
        return List.of(RecipientSeed.class);
    }
}

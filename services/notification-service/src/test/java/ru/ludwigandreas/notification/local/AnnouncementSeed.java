package ru.ludwigandreas.notification.local;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import ru.ludwigandreas.notification.web.dto.AudienceTypeDto;
import ru.ludwigandreas.notification.web.dto.PublishAnnouncementRequest;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixture;

/** One announcement to everyone, published through the service's own API, visible for a week. */
public class AnnouncementSeed implements SeedFixture {

    private static final Duration VISIBLE_FOR = Duration.ofDays(7);

    private final LocalApi api;

    public AnnouncementSeed(LocalApi api) {
        this.api = api;
    }

    @Override
    public void seed() {
        api.post(LocalCaller.ADMIN, "/api/v1/announcements", new PublishAnnouncementRequest(
                "platform-release", AudienceTypeDto.EVERYONE, null, "platform-release",
                Map.of("productName", "Ludwig", "version", "2.0.0"), null,
                Instant.now().plus(VISIBLE_FOR)));
    }

    @Override
    public List<Class<? extends SeedFixture>> after() {
        return List.of(RecipientSeed.class);
    }
}

package ru.ludwigandreas.example.catalog.service.notification;

import java.util.List;
import java.util.Set;
import ru.ludwigandreas.example.catalog.client.dto.NotificationCategoryClass;
import ru.ludwigandreas.example.catalog.client.dto.NotificationChannel;
import ru.ludwigandreas.example.catalog.client.dto.NotificationPriority;

/**
 * What the service layer needs to know about steward notifications.
 *
 * <p>An interface rather than the {@code @ConfigurationProperties} class itself, and not for
 * decoration: this service's architecture rules require properties classes to live in the
 * configuration package and require the configuration package to be free of cycles with the layers
 * below it. A service class that depended on the properties type directly would create exactly that
 * cycle - configuration constructs the service, the service reads configuration - and the rule would
 * fail the build, correctly.
 *
 * <p>Declaring the capability here and letting the properties class implement it points both
 * dependencies the same way. It also means the service layer states what it needs rather than what
 * happens to be configurable, and a test can supply four values without building a properties object.
 */
public interface StewardNotificationSettings {

    /** Whether a published product produces a notification request at all. */
    boolean isEnabled();

    /** Template family on the notification service. */
    String getTemplateKey();

    /** What the recipient's preferences are expressed against. */
    String getCategory();

    /** Whether recipients may decline these. */
    NotificationCategoryClass getCategoryClass();

    /** Which queue lane to use. */
    NotificationPriority getPriority();

    /** Channels to fan out to. */
    Set<NotificationChannel> getChannels();

    /** Platform user ids of the stewards who hear about a new product. */
    List<String> getStewardUserIds();
}

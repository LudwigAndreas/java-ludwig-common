package ru.ludwigandreas.example.catalog.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import ru.ludwigandreas.example.catalog.client.dto.NotificationCategoryClass;
import ru.ludwigandreas.example.catalog.client.dto.NotificationChannel;
import ru.ludwigandreas.example.catalog.client.dto.NotificationPriority;
import ru.ludwigandreas.example.catalog.service.notification.StewardNotificationSettings;

/**
 * What this service asks the notification service for when a product is published.
 *
 * <p>Every value here is a deployment decision rather than a domain one: which template, which
 * category, who hears about it. Hard-coding any of them would mean a copy change or a new steward
 * mailbox is a release of this service.
 *
 * <p>Implements {@link StewardNotificationSettings} so the service layer depends on the capability
 * rather than on this class: pointing both dependencies at the interface is what keeps the
 * configuration package and the service package out of a cycle.
 *
 * <p>{@code @Validated} with constraints on the fields, so a ConfigMap that switches notifications on
 * without naming a steward fails the context at startup rather than dispatching requests with an
 * empty recipient list that the notification service then rejects one row at a time. This service's
 * own architecture rules enforce both that and the package this class lives in.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ludwig.catalog.notifications")
public class CatalogNotificationProperties implements StewardNotificationSettings {

    /**
     * Whether a published product produces a notification request at all.
     *
     * <p>Off by default, for the same reason the notification service's own event publishing is: an
     * environment without a reachable notification service would otherwise accumulate outbox rows
     * that can never be dispatched, and the table would grow until somebody noticed.
     */
    private boolean enabled;

    /** Template family on the notification service, e.g. {@code catalog-product-published}. */
    @NotBlank
    private String templateKey = "catalog-product-published";

    /** What the recipient's preferences are expressed against on the notification service. */
    @NotBlank
    private String category = "catalog";

    /** Whether recipients may decline these. A catalogue announcement is declinable. */
    @NotNull
    private NotificationCategoryClass categoryClass = NotificationCategoryClass.MARKETING;

    /** Which queue lane to use. Nobody is waiting on a catalogue announcement. */
    @NotNull
    private NotificationPriority priority = NotificationPriority.BULK;

    /** Channels to fan out to. */
    @NotEmpty
    private Set<NotificationChannel> channels = Set.of(NotificationChannel.EMAIL);

    /**
     * Platform user ids of the catalogue stewards who hear about a new product.
     *
     * <p>User ids rather than addresses: the notification service owns the address, the locale and
     * the recipient's preferences, and a copy of an address held here is one that goes stale without
     * anything noticing.
     */
    private List<String> stewardUserIds = List.of();

    /**
     * Requires at least one steward, but only once notifications are switched on.
     *
     * <p>A conditional constraint rather than {@code @NotEmpty}, because the two states are genuinely
     * different configurations and not one with a missing value. Notifications are off by default, so
     * an unconditional constraint would make every deployment that does not use the feature - and
     * every test that boots this service - fail to start over a list nobody is reading.
     *
     * <p>Switching the feature on without naming a steward still fails the context, which is the case
     * the check exists for: the alternative is a request per published product that the notification
     * service rejects for having no recipients, discovered one dead-lettered row at a time.
     *
     * @return whether the recipient list is consistent with the enabled flag
     */
    @AssertTrue(message = "ludwig.catalog.notifications.steward-user-ids must name at least one steward"
            + " when notifications are enabled")
    public boolean isStewardListConsistent() {
        return !enabled || !stewardUserIds.isEmpty();
    }
}

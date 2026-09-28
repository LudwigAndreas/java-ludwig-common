package ru.ludwigandreas.example.catalog.client.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What this service asks the notification service to send.
 *
 * <h2>Why this is a copy rather than a shared artifact</h2>
 *
 * <p>The notification service owns an identically shaped record. Depending on its jar to reuse that
 * record would make a catalogue release depend on a notification release, drag that service's entire
 * transitive tree onto this classpath, and mean a field added for some other caller becomes a
 * recompile here. A consumer-owned copy of the subset actually used is the standard trade: it is a
 * few records, it says exactly what this service sends, and the wire contract - not a Java type - is
 * what the two services agree on.
 *
 * <p>The cost is that a breaking change to the contract is found by a test rather than by the
 * compiler, which is why {@code CatalogNotificationContractTest} exists.
 *
 * <p>Note what is not here: a subject or a body. A caller that could submit rendered text would be
 * deciding the notification service's wording and bypassing its localization. A template key and a
 * variable map is the contract.
 *
 * @param templateKey   template family, e.g. {@code catalog-product-published}
 * @param category      what the recipient's preferences are expressed against, e.g. {@code catalog}
 * @param categoryClass whether the recipient may decline this
 * @param priority      which queue lane to use
 * @param channels      channels to fan out to
 * @param recipients    who to notify
 * @param variables     values the template renders against
 */
public record SendNotificationRequest(String templateKey,
                                      String category,
                                      NotificationCategoryClass categoryClass,
                                      NotificationPriority priority,
                                      Set<NotificationChannel> channels,
                                      List<NotificationRecipient> recipients,
                                      Map<String, Object> variables) {
}

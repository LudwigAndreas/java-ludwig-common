package ru.ludwigandreas.notification.service.recipient;

import java.util.Locale;
import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * One recipient, turned into everything needed to address and render for them.
 *
 * @param userId       the subject, or null for a literal address with no account behind it
 * @param address      the resolved destination on {@link #channel}
 * @param displayName  from the identity projection; null when the user is unknown there
 * @param known        whether the identity projection had a record. False is not an error - see
 *                     {@link DefaultRecipientResolver} - it only means the notification goes out
 *                     without the enrichment a directory record would have added
 * @param quietHours   evaluated in the recipient's own zone, never the server's
 */
public record ResolvedRecipient(
        String userId,
        ChannelType channel,
        String address,
        Locale locale,
        java.time.ZoneId zone,
        String displayName,
        String tenantId,
        boolean known,
        QuietHours quietHours) {
}

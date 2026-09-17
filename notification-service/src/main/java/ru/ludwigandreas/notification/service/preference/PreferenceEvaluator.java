package ru.ludwigandreas.notification.service.preference;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.repository.RecipientPreferenceRepository;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.RecipientPreferenceEntity;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.recipient.QuietHours;
import ru.ludwigandreas.notification.service.recipient.ResolvedRecipient;

/**
 * Decides whether a resolved recipient wants this notification, now.
 *
 * <h2>The bypass, and why it is a category property rather than a flag on the request</h2>
 *
 * <p>A {@link CategoryClass#TRANSACTIONAL} notification ignores preferences and quiet hours. If that
 * decision were a boolean on the request, every calling service would set it, and every calling
 * service would set it to true - because from inside any one service its own notification always
 * looks important. Making it a property of the <em>category</em> means the decision is made once, in
 * configuration, by whoever owns the notification catalogue, and a service that wants its campaign
 * exempted has to argue for it rather than pass a flag.
 *
 * <p>The suppression list is checked separately and has no bypass at all - see
 * {@link SuppressionService}. That is the one rule a transactional notification cannot override,
 * because a hard bounce is a fact about the address rather than a wish of its owner.
 *
 * <h2>Precedence</h2>
 *
 * <p>Four rows can bear on one decision: the exact {@code (category, channel)} pair, the category on
 * every channel, the wildcard category on this channel, and the wildcard on everything. The most
 * specific one wins, which is what lets a recipient say "nothing at all, except order updates by
 * email" - two rows, no deletion, and the record of what they originally asked for survives.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreferenceEvaluator {

    /** The category value meaning "everything I am allowed to decline". */
    public static final String WILDCARD_CATEGORY = "*";

    /**
     * Specificity score of one preference row: an exact category and an exact channel beat a
     * wildcard on either axis, and a row naming both beats a row naming one.
     */
    private static final Comparator<RecipientPreferenceEntity> BY_SPECIFICITY = Comparator
            .comparingInt((RecipientPreferenceEntity row) ->
                    WILDCARD_CATEGORY.equals(row.getCategory()) ? 0 : 2)
            .thenComparingInt(row -> row.getChannel() == null ? 0 : 1)
            // A deterministic tie-break, so two equally specific contradictory rows always resolve
            // the same way rather than differing between replicas.
            .thenComparing(RecipientPreferenceEntity::getId);

    private final RecipientPreferenceRepository preferenceRepository;
    private final NotificationProperties properties;

    /**
     * @param category      the business category, e.g. {@code order-updates}
     * @param categoryClass whether the recipient may decline it at all
     * @param now           evaluated against the recipient's quiet window in their own zone
     */
    @Transactional(readOnly = true)
    public DispatchDecision evaluate(ResolvedRecipient recipient, String category,
                                     CategoryClass categoryClass, Instant now) {
        if (categoryClass == CategoryClass.TRANSACTIONAL) {
            return DispatchDecision.allowed();
        }
        if (recipient.userId() != null && isOptedOut(recipient, category)) {
            return DispatchDecision.suppressed(DispatchDecision.Reasons.OPT_OUT);
        }
        return quietHoursDecision(recipient.quietHours(), now);
    }

    private boolean isOptedOut(ResolvedRecipient recipient, String category) {
        List<RecipientPreferenceEntity> applicable = preferenceRepository.findApplicable(
                recipient.userId(), category, ChannelKind.valueOf(recipient.channel().name()));
        Optional<RecipientPreferenceEntity> winner = applicable.stream().max(BY_SPECIFICITY);
        // No row at all means allowed. Opting out is the exception, so absence is the permissive
        // answer - the alternative would make a service with an empty preference table silent.
        return winner.isPresent() && !winner.get().isAllowed();
    }

    private DispatchDecision quietHoursDecision(QuietHours quietHours, Instant now) {
        if (!properties.getPreferences().isQuietHoursEnabled() || !quietHours.contains(now)) {
            return DispatchDecision.allowed();
        }
        if (!properties.getPreferences().isQuietHoursDefer()) {
            return DispatchDecision.suppressed(DispatchDecision.Reasons.QUIET_HOURS);
        }
        return DispatchDecision.deferred(quietHours.nextOpening(now),
                DispatchDecision.Reasons.QUIET_HOURS);
    }
}

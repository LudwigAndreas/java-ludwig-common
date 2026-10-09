package ru.ludwigandreas.notification.service.announcement;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.ludwigandreas.notification.service.exception.AudienceNotPermittedException;
import ru.ludwigandreas.notification.service.model.Audience;
import ru.ludwigandreas.notification.service.model.AudienceType;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * The one place a requested audience is checked against what the deployment permits.
 *
 * <h2>Why this is one method and an ArchUnit rule protects it</h2>
 *
 * <p>Everything here is a policy check, and policy checks fail silently in the direction that
 * matters. A second code path that constructed a role audience without passing through this method
 * would work perfectly for every role on the allowlist - every test would pass, every valid publish
 * would succeed - and would also accept every role that is not on it. Nothing about the behaviour
 * would look wrong until somebody announced something to a role nobody meant to be addressable.
 *
 * <p>So the rule is structural rather than a comment: no class outside this package may construct a
 * role-targeted {@link Audience}.
 *
 * <h2>Why the allowlist is an allowlist</h2>
 *
 * <p>"Any role the directory knows about" would be the obvious design and it is wrong twice over.
 * A free-form role target is an <b>enumeration primitive</b>: an announcer can discover which roles
 * exist by publishing to guesses and watching which succeed. And some roles' membership is itself
 * the sensitive fact - "everyone under investigation" is a role somebody will eventually create, and
 * being able to address it is being able to learn who is in it.
 *
 * <p>No build can check that a role on the list <em>should</em> be addressable. What is checked is
 * that the list is consulted, here, once.
 *
 * <h2>Targeting is not publishing</h2>
 *
 * <p>Whether a caller may publish at all is resource-level authorization on the endpoint. This is a
 * separate question about the <em>content</em> of a publish, and a caller can hold the publishing
 * role and still be refused an audience - which is the point: the publishing role says "this person
 * may make announcements", not "this person may make any announcement".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementAudienceResolver {

    private final NotificationProperties properties;

    /**
     * Turns a requested kind and value into an {@link Audience}, or refuses.
     *
     * @param requestedType  the audience kind the caller asked for
     * @param requestedValue the role code, for a role audience
     * @throws AudienceNotPermittedException when the kind is not permitted or the role is not on the
     *                                       allowlist. One exception for both, carrying neither
     *                                       detail - see its javadoc for why the response must not
     *                                       distinguish them
     */
    public Audience resolve(AudienceType requestedType, String requestedValue) {
        NotificationProperties.Announcements announcements = properties.getAnnouncements();

        if (!announcements.getAllowedAudiences().contains(requestedType.name())) {
            // Logged with the detail the response withholds. An operator needs to know which half
            // failed; a caller must not be able to tell.
            log.warn("Refused announcement audience {}: not in announcements.allowed-audiences {}",
                    requestedType, announcements.getAllowedAudiences());
            throw new AudienceNotPermittedException();
        }

        if (requestedType == AudienceType.EVERYONE) {
            return Audience.everyone();
        }

        // Exact match, case-sensitively, against the configured codes. Deliberately not normalised:
        // the projection stores role codes exactly as the directory names them, and a resolver that
        // matched case-insensitively would permit targeting "admin" when the reviewed list said
        // "ADMIN" - a difference that is invisible in a diff of the configuration.
        List<String> targetable = announcements.getTargetableRoles();
        if (requestedValue == null || !targetable.contains(requestedValue)) {
            log.warn("Refused announcement role target: not in announcements.targetable-roles {}",
                    targetable);
            throw new AudienceNotPermittedException();
        }
        return Audience.ofRole(requestedValue);
    }
}

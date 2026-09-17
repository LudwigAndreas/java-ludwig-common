package ru.ludwigandreas.notification.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * One recipient: a user id to resolve, or a literal destination.
 *
 * @param userId    the OIDC subject - the form to prefer, because it lets this service resolve the
 *                  address, the language and the quiet hours from one place rather than having six
 *                  calling services each keep their own stale copy
 * @param address   a literal destination, for recipients this platform has no account for
 * @param locale    BCP 47 override; normally omitted, since the recipient's own profile decides
 * @param timezone  IANA zone override; normally omitted, for the same reason
 * @param variables template variables for this recipient specifically, merged over the request-wide
 *                  ones - what makes greeting five people by name one request rather than five
 */
public record RecipientDto(

        @Size(max = 255, message = "{notification.validation.recipient.user-id.size}")
        String userId,

        @Size(max = 512, message = "{notification.validation.recipient.address.size}")
        String address,

        @Size(max = 35, message = "{notification.validation.recipient.locale.size}")
        String locale,

        @Size(max = 64, message = "{notification.validation.recipient.timezone.size}")
        String timezone,

        Map<String, Object> variables) {

    /**
     * Exactly one of {@code userId} and {@code address} must be present.
     *
     * <p>Rejecting "both" matters as much as rejecting "neither": a payload carrying a user id and an
     * address is ambiguous, and whichever one this service silently preferred would be wrong half the
     * time - in one direction sending to an address the caller did not intend, and in the other
     * ignoring an override they did.
     */
    @AssertTrue(message = "{notification.validation.recipient.exactly-one}")
    public boolean isExactlyOneIdentifier() {
        boolean hasUser = userId != null && !userId.isBlank();
        boolean hasAddress = address != null && !address.isBlank();
        return hasUser ^ hasAddress;
    }
}

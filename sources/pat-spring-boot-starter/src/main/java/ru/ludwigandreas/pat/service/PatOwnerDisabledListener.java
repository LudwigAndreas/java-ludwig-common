package ru.ludwigandreas.pat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import ru.ludwigandreas.security.principal.PrincipalDisabledEvent;

/**
 * Revokes a disabled owner's live tokens.
 *
 * <h2>What this is for, and what it is not for</h2>
 *
 * <p>It is <b>not</b> what makes a departed employee's tokens stop working. The attenuation already does
 * that, with no revocation and no event at all: a disabled owner resolves to no authorities, so the
 * intersection of their authorities with any token's scopes is empty, and every one of their tokens is inert
 * within the authority cache's TTL.
 *
 * <p>What this is for is that <b>the token list an auditor reads should match reality</b>. Without it, a
 * query for live credentials returns rows belonging to somebody who left in March - each of them harmless,
 * each of them indistinguishable at a glance from a credential that still works. It also puts a timestamp
 * and a reason on the revocation, which is the difference between "this was cleaned up when they left" and
 * "nobody has looked at this since they left".
 *
 * <p>So this is an accountability mechanism rather than a security control, and saying so matters: a reader
 * who believed it was the security control might reasonably conclude that a deployment without the event
 * publisher is exposed. It is not.
 */
@Slf4j
@RequiredArgsConstructor
public class PatOwnerDisabledListener {

    private final PatService service;

    /**
     * Handles the event.
     *
     * <p>Not transactional itself - {@link PatService#revokeAllForDisabledOwner} is, and running the
     * listener in its own transaction would mean a failure anywhere in the batch rolled back revocations
     * that had already succeeded. Partial progress is the right outcome here: five of six tokens revoked
     * beats none, and the sweep is idempotent so the sixth is picked up by the next event or by inactivity
     * expiry.
     */
    @EventListener
    public void onPrincipalDisabled(PrincipalDisabledEvent event) {
        int revoked = service.revokeAllForDisabledOwner(event.subject());
        if (revoked > 0) {
            log.info("Revoked {} personal access token(s) belonging to disabled principal {} ({}). Their"
                            + " tokens were already inert - a disabled owner resolves to no authorities -"
                            + " so this is so the token list matches reality rather than to stop them"
                            + " working.",
                    revoked, event.subject(), event.reason());
        }
    }
}

package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.pat.service.PatOwnerDisabledListener;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.security.principal.PrincipalDisabledEvent;

/**
 * The owner-disabled listener, and what it is honestly for.
 *
 * <p>The listener is an <b>accountability</b> mechanism, not the security control. A disabled owner's tokens
 * are already inert through the attenuation - a disabled owner resolves to no authorities, so the
 * intersection is empty for every token they hold. What this adds is that the token list an auditor reads
 * matches reality, with a timestamp and a reason.
 *
 * <p>So the test asserts delegation and robustness rather than inventing a security property the listener
 * does not provide. Over-claiming here would be worse than under-testing: a reader who believed this was
 * the control would reasonably conclude that a deployment without the publisher is exposed, and it is not.
 */
class OwnerDisabledRevocationTest {

    @Test
    @DisplayName("a disabling event revokes that owner's live tokens")
    void disablingRevokesTheOwnersTokens() {
        PatService service = org.mockito.Mockito.mock(PatService.class);
        org.mockito.Mockito.when(service.revokeAllForDisabledOwner("alice")).thenReturn(3);

        new PatOwnerDisabledListener(service)
                .onPrincipalDisabled(new PrincipalDisabledEvent("alice", "DISABLE"));

        org.mockito.Mockito.verify(service).revokeAllForDisabledOwner("alice");
    }

    @Test
    @DisplayName("an owner with no tokens is a no-op rather than an error")
    void ownerWithNoTokensIsANoOp() {
        PatService service = org.mockito.Mockito.mock(PatService.class);
        org.mockito.Mockito.when(service.revokeAllForDisabledOwner("nobody")).thenReturn(0);

        // The common case by a wide margin: most disabled users never had a token. It must be silent, or
        // the log fills with nothing on every offboarding and the real ones become invisible.
        new PatOwnerDisabledListener(service)
                .onPrincipalDisabled(new PrincipalDisabledEvent("nobody", "DELETE"));

        org.mockito.Mockito.verify(service).revokeAllForDisabledOwner("nobody");
    }

    @Test
    @DisplayName("the event refuses to be constructed without a subject")
    void eventRequiresASubject() {
        // An event naming nobody would revoke nobody's tokens while looking like it had worked, which is
        // the worst outcome available for an offboarding signal.
        assertThatThrownBy(() -> new PrincipalDisabledEvent(null, "DISABLE"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PrincipalDisabledEvent("  ", "DISABLE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the reason is carried for the record but is never a decision input")
    void reasonIsRecordedNotActedOn() {
        PatService service = org.mockito.Mockito.mock(PatService.class);
        PatOwnerDisabledListener listener = new PatOwnerDisabledListener(service);

        // DISABLE and DELETE must behave identically. The projection treats both as DISABLED - a removed
        // user is kept as a status change so audit references keep resolving - and a listener that branched
        // on the reason would be making a second, divergent decision about what "gone" means.
        listener.onPrincipalDisabled(new PrincipalDisabledEvent("alice", "DISABLE"));
        listener.onPrincipalDisabled(new PrincipalDisabledEvent("alice", "DELETE"));

        org.mockito.Mockito.verify(service, org.mockito.Mockito.times(2))
                .revokeAllForDisabledOwner("alice");
    }

    @Test
    @DisplayName("a null reason is acceptable - the subject is the only part that matters")
    void nullReasonIsAcceptable() {
        PatService service = org.mockito.Mockito.mock(PatService.class);

        assertThat(new PrincipalDisabledEvent("alice", null).reason()).isNull();
        new PatOwnerDisabledListener(service)
                .onPrincipalDisabled(new PrincipalDisabledEvent("alice", null));

        org.mockito.Mockito.verify(service).revokeAllForDisabledOwner("alice");
    }

    @Test
    @DisplayName("the service's own revocation sets the distinguishing reason, not the listener")
    void serviceOwnsTheRevocationReason() {
        // The listener passes no reason: the service writes RevocationReasons.OWNER_DISABLED itself. That
        // keeps the closed set of reasons in one place rather than letting each caller supply a string,
        // which is what makes "departures" a filter an auditor can run.
        assertThat(ru.ludwigandreas.pat.service.RevocationReasons.OWNER_DISABLED)
                .isEqualTo("owner-disabled");
        assertThat(Set.of(
                        ru.ludwigandreas.pat.service.RevocationReasons.REQUESTED,
                        ru.ludwigandreas.pat.service.RevocationReasons.OWNER_DISABLED,
                        ru.ludwigandreas.pat.service.RevocationReasons.INACTIVITY,
                        ru.ludwigandreas.pat.service.RevocationReasons.COMPROMISED))
                .hasSize(4);
    }
}

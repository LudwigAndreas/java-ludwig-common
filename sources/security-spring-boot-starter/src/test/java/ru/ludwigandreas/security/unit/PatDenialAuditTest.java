package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import ru.ludwigandreas.audit.AuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.audit.AccessDecision;
import ru.ludwigandreas.security.principal.CredentialKind;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * That a denial record names the credential beside the actor.
 *
 * <p>The reason this exists: a token-backed request's authority is the intersection of its owner's
 * <b>live</b> authority and the token's scopes, so a token can stop working with nobody having revoked
 * anything. To whoever is paged that reads as a bug in the token. A record saying only "alice was denied"
 * sends the investigation to the token; a record that names both the token and that the authority behind it
 * went away sends it to the demotion, which is where the answer is.
 */
class PatDenialAuditTest {

    private static AccessDecision.AccessDecisionBuilder denial() {
        return AccessDecision.builder()
                .subject("alice")
                .principalType(PrincipalType.USER)
                .resourceType("Order")
                .action("read")
                .scopeAccess("NONE")
                .granted(false)
                .reason("insufficient-authority");
    }

    @Test
    @DisplayName("a token-backed denial carries the credential kind and the token id")
    void tokenBackedDenialNamesTheCredential() {
        AuditEvent event = denial()
                .credentialKind(CredentialKind.PERSONAL_ACCESS_TOKEN.name())
                .credentialId("pat-42")
                .build()
                .toAuditEvent();

        assertThat(event.attributes())
                .containsEntry("credentialKind", "PERSONAL_ACCESS_TOKEN")
                .containsEntry("credentialId", "pat-42");
    }

    @Test
    @DisplayName("the actor stays the human - the credential sits beside them, not instead of them")
    void actorIsStillTheOwner() {
        AuditEvent event = denial()
                .credentialKind(CredentialKind.PERSONAL_ACCESS_TOKEN.name())
                .credentialId("pat-42")
                .build()
                .toAuditEvent();

        // Who is accountable and what was presented are different questions. Replacing the actor with the
        // token would lose the first; omitting the credential loses the second.
        assertThat(event.actor().subject()).isEqualTo("alice");
        assertThat(event.actor().principalType()).isEqualTo(PrincipalType.USER.name());
    }

    @Test
    @DisplayName("an ordinary denial carries no credential attributes at all")
    void ordinaryDenialIsUnchanged() {
        AuditEvent event = denial().build().toAuditEvent();

        assertThat(event.attributes())
                .doesNotContainKeys("credentialKind", "credentialId")
                .containsEntry("scopeAccess", "NONE");
    }

    @Test
    @DisplayName("the record still carries no payload, header or token, which was its original rule")
    void stillCarriesNoSecret() {
        AuditEvent event = denial()
                .credentialKind(CredentialKind.PERSONAL_ACCESS_TOKEN.name())
                .credentialId("pat-42")
                .build()
                .toAuditEvent();

        // The credential ID is an identifier an operator revokes by, not credential material. An audit trail
        // is retained for years and read by people not entitled to the payloads, so the rule that it must
        // not become a second copy of what it guards still holds with the credential added.
        assertThat(event.attributes().keySet())
                .containsExactlyInAnyOrder("scopeAccess", "credentialKind", "credentialId");
    }
}

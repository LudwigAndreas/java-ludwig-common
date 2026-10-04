package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.CredentialKind;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * The credential dimension, and the compatibility claim the whole decision rests on.
 *
 * <p>The first test is the load-bearing one: the pre-existing one-argument constructor must behave exactly as
 * it did, because the entire argument for putting the credential here rather than on {@link LudwigPrincipal}
 * was that it costs no consumer anything.
 */
class LudwigAuthenticationTest {

    private static LudwigPrincipal alice() {
        return LudwigPrincipal.builder()
                .subject("alice")
                .type(PrincipalType.USER)
                .role("ROLE_READER")
                .permission("orders:read")
                .build();
    }

    @Test
    @DisplayName("the pre-existing constructor still works and reports no long-lived credential")
    void preExistingConstructorIsUnchanged() {
        LudwigAuthentication authentication = new LudwigAuthentication(alice());

        assertThat(authentication.credential()).isEqualTo(Credential.DIRECT);
        assertThat(authentication.credential().isLongLived()).isFalse();
        assertThat(authentication.credential().credentialId()).isEmpty();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("alice");
        assertThat(authentication.getPrincipal()).isEqualTo(alice());
    }

    @Test
    @DisplayName("authorities are still the flattened roles and permissions, credential or not")
    void authoritiesAreUnchanged() {
        LudwigAuthentication direct = new LudwigAuthentication(alice());
        LudwigAuthentication tokenBacked =
                new LudwigAuthentication(alice(), Credential.personalAccessToken("pat-1"));

        assertThat(direct.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .containsExactlyInAnyOrder("ROLE_READER", "orders:read");
        // The credential does not touch authority. Narrowing happens before the principal is built, in the
        // one converter permitted to do it; this type only records what was presented.
        assertThat(tokenBacked.getAuthorities()).containsExactlyInAnyOrderElementsOf(direct.getAuthorities());
    }

    @Test
    @DisplayName("getCredentials stays null - the raw credential is still deliberately not held")
    void rawCredentialIsStillNotHeld() {
        assertThat(new LudwigAuthentication(alice(), Credential.personalAccessToken("pat-1")).getCredentials())
                .isNull();
    }

    @Test
    @DisplayName("a token-backed authentication reports the kind and the token id")
    void tokenBackedReportsItsCredential() {
        LudwigAuthentication authentication =
                new LudwigAuthentication(alice(), Credential.personalAccessToken("pat-42"));

        assertThat(authentication.credential().isKind(CredentialKind.PERSONAL_ACCESS_TOKEN)).isTrue();
        assertThat(authentication.credential().credentialId()).contains("pat-42");
        assertThat(authentication.credential().isLongLived()).isTrue();
    }

    @Test
    @DisplayName("the principal type is unchanged by the credential, so isType(USER) still matches")
    void principalTypeIsOrthogonalToCredential() {
        LudwigAuthentication authentication =
                new LudwigAuthentication(alice(), Credential.personalAccessToken("pat-1"));

        // The reason this is not a fourth PrincipalType. Every existing isType(USER) check and every
        // USER-keyed DataScopeProvider has to keep matching when the same person uses a token.
        assertThat(authentication.getPrincipal().isType(PrincipalType.USER)).isTrue();
        assertThat(authentication.getPrincipal().type()).isEqualTo(PrincipalType.USER);
    }

    @Test
    @DisplayName("a null credential is treated as direct rather than left null")
    void nullCredentialBecomesDirect() {
        assertThat(new LudwigAuthentication(alice(), null).credential()).isEqualTo(Credential.DIRECT);
    }

    @Test
    @DisplayName("a long-lived credential with no id is refused - such a request would be unauditable")
    void longLivedCredentialRequiresAnId() {
        assertThatThrownBy(() -> new Credential(CredentialKind.PERSONAL_ACCESS_TOKEN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry the id");
        assertThatThrownBy(() -> Credential.personalAccessToken("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isLongLived is phrased as not-direct, so a second credential kind is covered on arrival")
    void isLongLivedCoversFutureKinds() {
        // Written as an assertion about every kind rather than about the one that exists, because a policy
        // written as isKind(PERSONAL_ACCESS_TOKEN) would silently stop covering the management surface the
        // day a deploy key is added - and nobody would notice.
        for (CredentialKind kind : CredentialKind.values()) {
            Credential credential = kind == CredentialKind.DIRECT
                    ? Credential.DIRECT
                    : new Credential(kind, "some-id");
            assertThat(credential.isLongLived()).isEqualTo(kind != CredentialKind.DIRECT);
        }
    }
}

package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.security.audit.PrincipalActorResolver;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

class PrincipalActorResolverTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Actor resolve(LudwigPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new LudwigAuthentication(principal));
        return new PrincipalActorResolver().currentActor().orElseThrow();
    }

    @Test
    @DisplayName("a user's tenant is recorded on the actor")
    void userCarriesTenant() {
        Actor actor = resolve(LudwigPrincipal.builder().subject("alice").type(PrincipalType.USER).tenantId("acme")
                .build());

        assertThat(actor.subject()).isEqualTo("alice");
        assertThat(actor.tenant()).isEqualTo("acme");
    }

    @Test
    @DisplayName("a service principal without a tenant records none")
    void serviceWithoutTenant() {
        Actor actor = resolve(LudwigPrincipal.builder().subject("ops-sa").type(PrincipalType.SERVICE).build());

        assertThat(actor.principalType()).isEqualTo("SERVICE");
        assertThat(actor.tenant()).isNull();
    }
}

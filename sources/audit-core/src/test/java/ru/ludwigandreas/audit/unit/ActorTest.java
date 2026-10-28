package ru.ludwigandreas.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.Actor;

class ActorTest {

    @Test
    @DisplayName("an actor identified by subject alone, and the system actor, carry no tenant")
    void noTenantByDefault() {
        assertThat(Actor.of("alice").tenant()).isNull();
        assertThat(Actor.of("alice", "USER").tenant()).isNull();
        assertThat(Actor.system().tenant()).isNull();
    }

    @Test
    @DisplayName("acting on behalf of someone keeps the actor's own tenant")
    void onBehalfOfKeepsTheTenant() {
        Actor actor = new Actor("alice", "USER", "Alice", null, "acme").onBehalfOf("bob");

        assertThat(actor.onBehalfOf()).isEqualTo("bob");
        assertThat(actor.tenant()).isEqualTo("acme");
    }
}

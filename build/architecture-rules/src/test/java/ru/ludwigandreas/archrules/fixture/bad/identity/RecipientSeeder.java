package ru.ludwigandreas.archrules.fixture.bad.identity;

import ru.ludwigandreas.identity.repository.SecurityUserRepository;

/** Writes a user straight into the projection's table: reported. */
public class RecipientSeeder {

    private final SecurityUserRepository users;

    public RecipientSeeder(SecurityUserRepository users) {
        this.users = users;
    }

    public void seed(Object user) {
        users.save(user);
    }

    public void wipe() {
        users.deleteAll();
    }
}

package ru.ludwigandreas.archrules.fixture.bad.identity;

import java.util.Optional;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;

/** Reads the projection: allowed. */
public class RecipientReader {

    private final SecurityUserRepository users;

    public RecipientReader(SecurityUserRepository users) {
        this.users = users;
    }

    public Optional<Object> find(String subject) {
        return users.findById(subject);
    }
}

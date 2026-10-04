package ru.ludwigandreas.archrules.fixture.bad.credentials.catalog.issue;

import java.util.Optional;

/**
 * An ordinary repository, which has exactly the shape of a credential store and none of the meaning.
 *
 * <p>The first negative, and the reason both store and seam rules key on the <em>name</em> rather than on the
 * shape. Find by id, save, delete is the shape of every repository in the platform; a rule keyed on it would
 * flag all of them and be gone by the next release.
 */
public class OrderRepository {

    public Optional<String> findById(String id) {
        return Optional.ofNullable(id);
    }

    public void save(String order) {
        // nothing - the fixture exists for its shape
    }

    public void delete(String id) {
        // nothing - the fixture exists for its shape
    }
}

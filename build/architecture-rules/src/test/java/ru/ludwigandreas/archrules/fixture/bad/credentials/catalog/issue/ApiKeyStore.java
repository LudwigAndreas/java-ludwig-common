package ru.ludwigandreas.archrules.fixture.bad.credentials.catalog.issue;

import java.util.HashMap;
import java.util.Map;

/**
 * A second credential store, written locally because this module needed to keep some API keys.
 *
 * <p>The shape the first rule exists to catch, and it is worth noting how reasonable it looks: a map keyed on
 * a digest, a save and a lookup, no secret held in the clear. What is wrong with it is not the code - it is
 * that the platform already has a store for this, and a second one is a second revocation surface.
 */
public class ApiKeyStore {

    private final Map<String, String> digestsByOwner = new HashMap<>();

    public void save(String owner, String digest) {
        digestsByOwner.put(owner, digest);
    }

    public String find(String owner) {
        return digestsByOwner.get(owner);
    }
}

package ru.ludwigandreas.archrules.fixture.bad.credentials.catalog.issue;

/**
 * A second credential seam, inviting a deployment to supply credential material this platform never governs.
 *
 * <p>The shape the second rule exists to catch. An interface, so somebody is meant to implement it, named so
 * that what gets implemented is a source of secrets.
 */
public interface TokenSecretProvider {

    String secretFor(String owner);
}

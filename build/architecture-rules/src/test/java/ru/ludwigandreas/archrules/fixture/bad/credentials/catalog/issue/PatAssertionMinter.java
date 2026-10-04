package ru.ludwigandreas.archrules.fixture.bad.credentials.catalog.issue;

/**
 * The supported extension point, which must not be mistaken for a second seam.
 *
 * <p>The second negative, and the one most likely to be broken by a careless widening of the seam pattern.
 * A deployment supplying its own assertion minter is exactly what the design asks for - the starter ships
 * this interface so that an identity provider which already owns a signing key can keep owning it. The seam
 * pattern deliberately does not match {@code *Minter} or {@code *Signer} for this reason.
 */
public interface PatAssertionMinter {

    String mint(String subject, String audience);
}

package ru.ludwigandreas.security.authz;

/**
 * What the authentication filters call to find out what a caller may do.
 *
 * <p>A separate type from {@link AuthorityResolver} rather than the same one, because the two sides of
 * the seam have different owners. {@link AuthorityResolver} is the extension point a service or the
 * identity-projection module implements; this is the module-internal entry point, with caching,
 * metrics and null-handling already applied. Keeping them distinct means the autoconfiguration can
 * wrap the SPI without the wrapper competing with the thing it wraps for the same injection point -
 * and means a consumer replacing the SPI automatically gets the caching behavior rather than having to
 * reimplement it.
 */
@FunctionalInterface
public interface AuthorityLookup {

    Authorities lookup(PrincipalRef ref);
}

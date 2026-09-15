package ru.ludwigandreas.security.authn.mtls;

import java.util.Optional;

/**
 * Maps a verified client certificate to a partner (or peer service) this deployment knows.
 *
 * <p>Separate from {@link ru.ludwigandreas.security.authz.AuthorityResolver} on purpose: this answers
 * "whose certificate is this?", the other answers "what may they do?". Keeping them apart is what lets
 * a partner's certificate be rotated without touching their grants, and their grants be changed without
 * touching PKI.
 *
 * <p>An empty result means "this is a valid certificate from a CA we trust, but not one we have
 * registered" - which must end the request as unauthenticated. A trusted CA is not by itself an
 * authorization to call: anyone else that CA issues to would otherwise be a caller.
 */
@FunctionalInterface
public interface PartnerIdentityResolver {

    Optional<PartnerIdentity> resolve(ClientCertificateDetails certificate);
}

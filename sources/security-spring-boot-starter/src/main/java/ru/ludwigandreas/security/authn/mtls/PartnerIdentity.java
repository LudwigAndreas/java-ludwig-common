package ru.ludwigandreas.security.authn.mtls;

import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * The organization behind a client certificate, once the certificate has been mapped to something this
 * service has a grant for.
 *
 * <p>Certificates get rotated and re-issued; the partner id must not. It is the id that appears in
 * data-scope grants and in {@code partner_id} columns, so deriving it from something that changes at
 * renewal - a serial number, a fingerprint, an expiry-bearing DN - would silently revoke a partner's
 * access on the day their certificate is renewed. Map from a stable SAN (a SPIFFE id or a DNS name
 * the partner keeps) and treat the fingerprint as an audit detail, not an identity.
 *
 * @param partnerId   stable business id, used in grants and in row-level {@code partner_id} columns
 * @param displayName human-readable name for logs and audit records
 * @param type        normally {@link PrincipalType#PARTNER}; workload certificates from inside the mesh
 *                    resolve to {@link PrincipalType#SERVICE}
 */
public record PartnerIdentity(String partnerId, String displayName, PrincipalType type) {

    public static PartnerIdentity partner(String partnerId, String displayName) {
        return new PartnerIdentity(partnerId, displayName, PrincipalType.PARTNER);
    }

    public static PartnerIdentity service(String workloadId) {
        return new PartnerIdentity(workloadId, workloadId, PrincipalType.SERVICE);
    }
}

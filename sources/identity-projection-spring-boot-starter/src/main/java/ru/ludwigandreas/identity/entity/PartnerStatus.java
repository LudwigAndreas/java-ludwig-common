package ru.ludwigandreas.identity.entity;

/**
 * Whether a registered partner may still call.
 *
 * <p>Suspending is the fast lever during an incident - a suspected key compromise, a contract dispute -
 * and it takes effect within the authority cache TTL without anyone having to touch PKI or revoke a
 * certificate.
 */
public enum PartnerStatus {

    ACTIVE,

    SUSPENDED
}

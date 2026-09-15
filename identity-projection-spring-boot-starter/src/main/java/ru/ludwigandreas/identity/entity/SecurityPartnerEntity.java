package ru.ludwigandreas.identity.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.AuditedEntity;

/**
 * An external organization allowed to call this service with a client certificate.
 *
 * <p>The database equivalent of the security module's configuration-driven partner list, for deployments
 * that onboard partners without a release. It is an {@link AuditedEntity} rather than an
 * {@link ru.ludwigandreas.db.core.entity.ExternalEntity}: unlike users, partners are not projected from
 * anywhere - they are granted here, by someone, and who granted what and when is exactly what an auditor
 * will ask about.
 *
 * <p>{@link #code} is the stable partner id that appears in data-scope grants and in {@code partner_id}
 * columns on business rows; the certificate identifiers below are how a presented certificate is
 * recognized, and they are expected to change at every renewal while the code never does.
 */
@Getter
@Setter
@Entity
@Table(name = "security_partner")
public class SecurityPartnerEntity extends AuditedEntity<UUID> {

    /** Business id used in grants and in row-level {@code partner_id} columns. Unique, immutable. */
    @Column(name = "code", nullable = false, updatable = false, length = 128)
    private String code;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    /** Preferred identifier: survives renewal and cannot be reissued to someone else by the same CA. */
    @Column(name = "spiffe_id", length = 512)
    private String spiffeId;

    @Column(name = "dns_san", length = 255)
    private String dnsSan;

    @Column(name = "subject_dn", length = 512)
    private String subjectDn;

    /** Optional pin to one certificate. Stronger, but must be updated at every renewal. */
    @Column(name = "certificate_hash", length = 128)
    private String certificateHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PartnerStatus status = PartnerStatus.ACTIVE;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "security_partner_role",
            joinColumns = @JoinColumn(name = "partner_id", nullable = false))
    @Column(name = "role_code", nullable = false, length = 128)
    private Set<String> roles = new LinkedHashSet<>();

    public boolean isActive() {
        return status == PartnerStatus.ACTIVE;
    }
}

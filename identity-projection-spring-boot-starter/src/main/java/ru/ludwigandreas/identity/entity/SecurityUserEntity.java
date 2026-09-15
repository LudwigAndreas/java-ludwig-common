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
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.ExternalEntity;

/**
 * One user, as this service knows them: a local projection of the OIDC provider's directory, kept up to
 * date from the Kafka user stream.
 *
 * <p>Extends db-core's {@link ExternalEntity} because that is exactly what this is - a record whose id and
 * lifecycle belong to another system. The id <em>is</em> the OIDC {@code sub}, not a generated surrogate:
 * the whole point of the table is to be looked up by the identifier a token carries, and a surrogate key
 * would add a join to the hottest query in the service for no benefit. {@code sourceVersion} and
 * {@code sourceTimestamp} come from the event, which is what lets the projection reject events that
 * arrive out of order.
 *
 * <p>Only what authorization actually needs is stored. This is a copy of directory data living in a
 * service database, replicated to every service that uses this module, so every field is another place a
 * personal-data request has to reach - {@code displayName} for audit readability and nothing else by
 * default.
 */
@Getter
@Setter
@Entity
@Table(name = "security_user")
public class SecurityUserEntity extends ExternalEntity<String> {

    /** Human-readable label for audit records and admin screens. Never used in a decision. */
    @Column(name = "display_name", length = 255)
    private String displayName;

    /** Only when the deployment is multi-tenant; drives the {@code TENANT} scope dimension. */
    @Column(name = "tenant_id", length = 128)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserStatus status = UserStatus.ACTIVE;

    /**
     * Role codes as the provider names them; normalization to the {@code ROLE_} prefix happens on read,
     * in {@code Authorities}, so the table keeps the directory's own vocabulary and a change of
     * convention does not require a data migration.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "security_user_role",
            joinColumns = @JoinColumn(name = "user_id", nullable = false))
    @Column(name = "role_code", nullable = false, length = 128)
    private Set<String> roles = new LinkedHashSet<>();

    /**
     * Eager rather than lazy, deliberately: roles are read on essentially every authority resolution, so
     * lazy loading would buy nothing and cost a {@code LazyInitializationException} the first time a
     * resolver runs outside a transaction.
     */
    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}

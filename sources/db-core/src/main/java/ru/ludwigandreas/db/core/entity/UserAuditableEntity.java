package ru.ludwigandreas.db.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

import java.io.Serializable;

/**
 * Adds {@code createdBy}/{@code updatedBy} on top of {@link DateAuditableEntity}. The auditor type is fixed
 * to {@link String} (an identifier such as a username or subject), matching the {@code AuditorAware<String>}
 * bean wired by {@code DatabaseAutoConfiguration}.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class UserAuditableEntity<ID extends Serializable> extends DateAuditableEntity<ID> {

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;
}

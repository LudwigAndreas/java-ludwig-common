package ru.ludwigandreas.reconciliation.integration.testmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

import java.util.UUID;

/** A consuming service's own scope row: the thing a partner-side collection hangs off. */
@Getter
@Setter
@Entity
@Table(name = "local_scope")
public class LocalScope extends GeneratedEntity<UUID> {

    @Column(name = "code", nullable = false, unique = true)
    private String code;
}

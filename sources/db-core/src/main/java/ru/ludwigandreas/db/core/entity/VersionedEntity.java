package ru.ludwigandreas.db.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@MappedSuperclass
public abstract class VersionedEntity<ID extends Serializable> extends JpaBaseEntity<ID> {

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}

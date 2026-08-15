package ru.ludwigandreas.db.core.entity;

import jakarta.persistence.MappedSuperclass;

import java.io.Serializable;

/**
 * Root of the "outer" entity family: data whose id and lifecycle are owned by an external system (received
 * over REST or Kafka), as opposed to the "inner" family ({@link GeneratedEntity}, {@link AuditedEntity} and
 * friends) for data created by this application. {@code ID} is intentionally left fully generic and never
 * defaulted to {@link java.util.UUID} here, since an external system may hand back a {@code Long}, a
 * {@code String}, a {@code UUID}, or any other identifier type.
 */
@MappedSuperclass
public abstract class ExternalEntity<ID extends Serializable> extends JpaBaseEntity<ID> {
}

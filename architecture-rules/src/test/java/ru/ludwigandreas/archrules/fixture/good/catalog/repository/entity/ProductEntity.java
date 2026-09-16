package ru.ludwigandreas.archrules.fixture.good.catalog.repository.entity;

import jakarta.persistence.Entity;

/** JPA entity, confined to the entity package and inheriting the audit columns. */
@Entity
public class ProductEntity extends BaseEntity {

    private String code;

    private String name;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}

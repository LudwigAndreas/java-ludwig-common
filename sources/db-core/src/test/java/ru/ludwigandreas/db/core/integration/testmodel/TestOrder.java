package ru.ludwigandreas.db.core.integration.testmodel;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.ludwigandreas.db.core.entity.AuditedEntity;

import java.util.UUID;

@Entity
@Table(name = "test_order")
public class TestOrder extends AuditedEntity<UUID> {

    private String description;

    protected TestOrder() {
    }

    public TestOrder(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}

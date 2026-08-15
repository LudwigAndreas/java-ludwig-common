package ru.ludwigandreas.db.core.integration.testmodel;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import ru.ludwigandreas.db.core.entity.SnapshotEntity;

/**
 * Uses a {@link String} id (rather than {@link java.util.UUID}) to demonstrate that "outer" entities are not
 * defaulted/locked to UUID ids the way {@link ru.ludwigandreas.db.core.entity.GeneratedEntity} is.
 */
@Entity
@Table(name = "test_snapshot")
public class TestSnapshot extends SnapshotEntity<String> {

    private String payload;

    protected TestSnapshot() {
    }

    public TestSnapshot(String externalId, String sourceSystem, String payload) {
        setId(externalId);
        setSourceSystem(sourceSystem);
        this.payload = payload;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}

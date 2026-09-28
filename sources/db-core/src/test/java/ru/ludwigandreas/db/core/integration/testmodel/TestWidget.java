package ru.ludwigandreas.db.core.integration.testmodel;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ru.ludwigandreas.db.core.entity.SoftDeleteEntity;

import java.util.UUID;

@Entity
@Table(name = "test_widget")
@SQLDelete(sql = "UPDATE test_widget SET deleted = true, deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted = false")
public class TestWidget extends SoftDeleteEntity<UUID> {

    private String name;

    protected TestWidget() {
    }

    public TestWidget(String name) {
        setId(UUID.randomUUID());
        this.name = name;
    }

    public String getName() {
        return name;
    }
}

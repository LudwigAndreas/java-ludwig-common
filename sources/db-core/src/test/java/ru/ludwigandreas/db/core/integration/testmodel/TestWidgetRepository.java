package ru.ludwigandreas.db.core.integration.testmodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TestWidgetRepository extends JpaRepository<TestWidget, UUID> {
}

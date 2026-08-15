package ru.ludwigandreas.db.core.integration.testmodel;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestSnapshotRepository extends JpaRepository<TestSnapshot, String> {
}

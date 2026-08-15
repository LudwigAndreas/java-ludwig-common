package ru.ludwigandreas.db.core.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.db.core.exception.IntegrityViolationException;
import ru.ludwigandreas.db.core.integration.testmodel.TestSnapshot;
import ru.ludwigandreas.db.core.integration.testmodel.TestSnapshotRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestApplication.class)
@Transactional
@Testcontainers
class SnapshotImmutabilityIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestSnapshotRepository snapshotRepository;

    @Test
    void fillsImportedAtOnPersistAndRejectsSubsequentUpdates() {
        TestSnapshot snapshot = new TestSnapshot("ext-1", "legacy-crm", "{}");
        TestSnapshot saved = snapshotRepository.saveAndFlush(snapshot);

        assertThat(saved.getImportedAt()).isNotNull();

        saved.setPayload("{\"changed\":true}");
        assertThatThrownBy(() -> snapshotRepository.saveAndFlush(saved))
                .isInstanceOf(IntegrityViolationException.class);
    }
}

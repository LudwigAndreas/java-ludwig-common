package ru.ludwigandreas.db.core.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.db.core.exception.IntegrityViolationException;
import ru.ludwigandreas.db.core.integration.testmodel.TestSnapshot;
import ru.ludwigandreas.db.core.integration.testmodel.TestSnapshotRepository;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestApplication.class)
@Transactional
@Import(PostgresContainerConfiguration.class)
class SnapshotImmutabilityIntegrationTest {

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

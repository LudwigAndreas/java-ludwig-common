package ru.ludwigandreas.db.core.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
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

    /**
     * Pinned by name, version <em>and</em> digest: a tag alone can be re-pointed at different
     * content, so tests (and everything else that runs a container) would silently change what they
     * execute.
     */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");


    // The connection name is given explicitly because Spring Boot otherwise deduces it by parsing
    // the image name, and a name carrying both a tag and a digest is not parseable as a repository.
    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);

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

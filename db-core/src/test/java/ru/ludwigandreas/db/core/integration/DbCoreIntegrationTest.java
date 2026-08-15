package ru.ludwigandreas.db.core.integration;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.db.core.exception.EntityNotFoundException;
import ru.ludwigandreas.db.core.integration.testmodel.TestOrder;
import ru.ludwigandreas.db.core.integration.testmodel.TestOrderRepository;
import ru.ludwigandreas.db.core.integration.testmodel.TestWidget;
import ru.ludwigandreas.db.core.integration.testmodel.TestWidgetRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the base entities against a real Postgres database: generated UUID ids, created/updated
 * auditing, optimistic locking, {@link BaseRepositoryImpl#getByIdOrThrow} and Hibernate
 * {@code @SQLDelete}/{@code @SQLRestriction}-backed soft delete.
 */
@SpringBootTest(classes = TestApplication.class)
@Transactional
@Testcontainers
class DbCoreIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestOrderRepository orderRepository;

    @Autowired
    private TestWidgetRepository widgetRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @WithMockUser(username = "alice")
    void generatesIdAndPopulatesAuditFieldsOnCreate() {
        TestOrder saved = orderRepository.saveAndFlush(new TestOrder("first order"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo("alice");
        assertThat(saved.getUpdatedBy()).isEqualTo("alice");
        assertThat(saved.getVersion()).isZero();
    }

    @Test
    @WithMockUser(username = "bob")
    void incrementsVersionAndUpdatesAuditorOnUpdate() {
        TestOrder saved = orderRepository.saveAndFlush(new TestOrder("v1"));

        saved.setDescription("v2");
        TestOrder updated = orderRepository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(1);
        assertThat(updated.getUpdatedBy()).isEqualTo("bob");
    }

    @Test
    void getByIdOrThrowThrowsForMissingId() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> orderRepository.getByIdOrThrow(missing))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    void softDeletedRowsAreExcludedFromQueriesButRemainInTheDatabase() {
        TestWidget widget = widgetRepository.saveAndFlush(new TestWidget("gadget"));
        UUID id = widget.getId();

        widgetRepository.delete(widget);
        entityManager.flush();

        assertThat(widgetRepository.findById(id)).isEmpty();

        Boolean deleted = (Boolean) entityManager
                .createNativeQuery("SELECT deleted FROM test_widget WHERE id = ?1")
                .setParameter(1, id)
                .getSingleResult();
        assertThat(deleted).isTrue();
    }
}

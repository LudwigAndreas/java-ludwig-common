package ru.ludwigandreas.odatafilter.integration;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ludwigandreas.odatafilter.testmodel.Department;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/**
 * End-to-end proof that the library works against a real Postgres database, exercised through
 * the same path a consuming application would use: HTTP request -> {@code ODataQuery<T>}
 * argument resolver -> {@link ru.ludwigandreas.odatafilter.core.ODataFilterService} -> QueryDSL
 * predicate -> JPQL -> Postgres. Runs in a single transaction per test (rolled back afterwards)
 * so fixture rows inserted in {@link #seed()} are visible to the MockMvc-driven request without
 * needing a commit, since both execute on the same thread/persistence context.
 */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
@Transactional
@Testcontainers
class ODataFilterIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seed() {
        Department engineering = new Department("Engineering");
        Department sales = new Department("Sales");
        entityManager.persist(engineering);
        entityManager.persist(sales);

        entityManager.persist(new Employee("Alice", 35, new BigDecimal("120000.00"), "ACTIVE", engineering));
        entityManager.persist(new Employee("Bob", 28, new BigDecimal("90000.00"), "ACTIVE", engineering));
        entityManager.persist(new Employee("Carol", 45, new BigDecimal("150000.00"), "INACTIVE", sales));
        entityManager.persist(new Employee("Dave", 22, new BigDecimal("60000.00"), "ACTIVE", sales));
        entityManager.flush();
    }

    @Test
    void filtersByComparison() throws Exception {
        mockMvc.perform(get("/employees").param("$filter", "age gt 30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Alice", "Carol")));
    }

    @Test
    void filtersAcrossToOneAssociation() throws Exception {
        mockMvc.perform(get("/employees").param("$filter", "department/name eq 'Sales'"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Carol", "Dave")));
    }

    @Test
    void combinesAndOrNotAndFunctions() throws Exception {
        mockMvc.perform(get("/employees")
                        .param("$filter", "status eq 'ACTIVE' and (age lt 25 or contains(name,'li'))"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Alice", "Dave")));
    }

    @Test
    void ordersDescendingAndLimitsWithTop() throws Exception {
        mockMvc.perform(get("/employees").param("$orderby", "age desc").param("$top", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Carol"))
                .andExpect(jsonPath("$[1].name").value("Alice"));
    }

    @Test
    void skipOffsetsIndependentlyOfTop() throws Exception {
        mockMvc.perform(get("/employees").param("$orderby", "age desc").param("$skip", "1").param("$top", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Alice"));
    }

    @Test
    void unknownFieldIsRejectedWith400() throws Exception {
        mockMvc.perform(get("/employees").param("$filter", "secretNotes eq 'x'"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedFilterIsRejectedWith400() throws Exception {
        mockMvc.perform(get("/employees").param("$filter", "age gt"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pageSizeAboveMaxIsRejectedWith400() throws Exception {
        mockMvc.perform(get("/employees").param("$top", "10000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void salaryFilterIsForbiddenWithoutAdminRole() throws Exception {
        mockMvc.perform(get("/employees").param("$filter", "salary gt 100000"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void salaryFilterSucceedsWithAdminRole() throws Exception {
        mockMvc.perform(get("/employees").param("$filter", "salary gt 100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Alice", "Carol")));
    }
}

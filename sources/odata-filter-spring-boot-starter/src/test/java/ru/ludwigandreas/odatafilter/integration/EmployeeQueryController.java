package ru.ludwigandreas.odatafilter.integration;

import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.testmodel.Employee;
import ru.ludwigandreas.webcore.web.PageResponse;

/**
 * Minimal controller used only by this module's own integration test, to exercise the full
 * request -> {@code ODataFilterService} -> QueryDSL predicate -> Postgres chain.
 *
 * <p>Written the way a consuming service should write it: the five OData options arrive as one
 * {@link ODataQueryOptions}, bound by this module's own argument resolver, and travel down unparsed -
 * so no JPA entity and no QueryDSL type appears in this signature.
 * {@link EmployeeQueryRepository} - the layer that owns {@link Employee} - is what turns them into a
 * predicate. The envelope is {@code web-core}'s {@code PageResponse}, which is where the caller reads
 * back its own {@code offset} and, unless it sent {@code $count=false}, its total.
 */
@RestController
@RequestMapping("/employees")
class EmployeeQueryController {

    private final EmployeeQueryRepository repository;

    EmployeeQueryController(EmployeeQueryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    PageResponse<EmployeeView> search(ODataQueryOptions options) {
        var page = repository.search(options).map(EmployeeView::of);
        return PageResponse.of(page.content(), page.offset(), page.size(), page.totalElements());
    }

    record EmployeeView(String name, Integer age, BigDecimal salary, String status, String department) {
        static EmployeeView of(Employee employee) {
            return new EmployeeView(
                    employee.getName(),
                    employee.getAge(),
                    employee.getSalary(),
                    employee.getStatus(),
                    employee.getDepartment() == null ? null : employee.getDepartment().getName());
        }
    }
}

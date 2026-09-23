package ru.ludwigandreas.odatafilter.integration;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.odatafilter.testmodel.Employee;

/**
 * Minimal controller used only by this module's own integration test, to exercise the full
 * request -> {@code ODataFilterService} -> QueryDSL predicate -> Postgres chain.
 *
 * <p>Written the way a consuming service should write it: the OData options arrive as plain
 * request parameters and travel down unparsed, so no JPA entity and no QueryDSL type appears in
 * this signature. {@link EmployeeQueryRepository} - the layer that owns {@link Employee} - is what
 * turns them into a predicate.
 */
@RestController
@RequestMapping("/employees")
class EmployeeQueryController {

    private final EmployeeQueryRepository repository;

    EmployeeQueryController(EmployeeQueryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    List<EmployeeView> search(
            @RequestParam(name = "$filter", required = false) String filter,
            @RequestParam(name = "$orderby", required = false) String orderBy,
            @RequestParam(name = "$top", required = false) Integer top,
            @RequestParam(name = "$skip", required = false) Integer skip) {
        return repository.search(new EmployeeSearchCriteria(filter, orderBy, top, skip))
                .stream()
                .map(EmployeeView::of)
                .toList();
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

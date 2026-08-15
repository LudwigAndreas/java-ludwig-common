package ru.ludwigandreas.odatafilter.testmodel;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;

@Entity
@Table(name = "employees")
@FilterPolicy(maxDepth = 4, maxPageSize = 50, defaultPageSize = 10, maxNestedPropertyDepth = 2)
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Filterable
    private String name;

    @Filterable
    private Integer age;

    /** Only ROLE_ADMIN may filter/sort on compensation data. */
    @Filterable(roles = "ROLE_ADMIN")
    private BigDecimal salary;

    @Filterable
    private String status;

    /** Deliberately not @Filterable - must stay unreachable regardless of $filter content. */
    private String secretNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    protected Employee() {
    }

    public Employee(String name, Integer age, BigDecimal salary, String status, Department department) {
        this.name = name;
        this.age = age;
        this.salary = salary;
        this.status = status;
        this.department = department;
        this.secretNotes = "classified";
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Integer getAge() {
        return age;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public String getStatus() {
        return status;
    }

    public Department getDepartment() {
        return department;
    }
}

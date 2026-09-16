package ru.ludwigandreas.archrules.fixture.bad.hexagonal.catalog.domain;

import com.google.gson.annotations.SerializedName;
import jakarta.persistence.Entity;
import org.springframework.stereotype.Component;

/** Violations: the domain model knows Spring, JPA and Jackson. */
@Entity
@Component
public class FrameworkBoundModel {

    @SerializedName("product_code")
    public String code;
}

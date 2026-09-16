package ru.ludwigandreas.archrules.fixture.bad.dtos.catalog.web.dto;

/** Violation: the API model can be changed after it was built and validated. */
public class MutableProductDto {

    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}

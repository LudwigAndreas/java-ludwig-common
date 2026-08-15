package ru.ludwigandreas.hotreload.binding;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Minimal bindable/validatable POJO shared by this package's tests. */
class SampleConfig {

    @NotBlank
    private String name;

    @Min(1)
    private int retries = 1;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }
}

package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Violates handler-declares-an-operation twice: a literal path and a placeholder resolved to its default. */
@RestController
@RequestMapping("/admin/v1/outbox")
public class UnannotatedAdminController {

    @GetMapping("/messages")
    public String messages() {
        return "messages";
    }

    @GetMapping("${fixture.admin-base-path:/admin/settings}/subjects")
    public String subjects() {
        return "subjects";
    }

    @GetMapping("${fixture.unresolvable}/elsewhere")
    public String unknowable() {
        return "skipped, not guessed at";
    }
}

package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.admin.AdminAccess;

/** Violates mapped-under-the-versioned-prefix: annotated, but mapped where the guard never runs. */
@RestController
@RequestMapping("/api/v1/locks")
public class MisplacedAdminController {

    @GetMapping
    @AdminAccess("locks.read")
    public String list() {
        return "locks";
    }
}

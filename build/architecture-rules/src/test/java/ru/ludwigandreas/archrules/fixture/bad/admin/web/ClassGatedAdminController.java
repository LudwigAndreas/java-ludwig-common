package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.admin.AdminAccess;

/** Violates no-second-gate on the class. */
@RestController
@RequestMapping("/admin/v1/leases")
@PreAuthorize("hasRole('ADMIN')")
public class ClassGatedAdminController {

    @GetMapping
    @AdminAccess("leases.read")
    public String list() {
        return "leases";
    }
}

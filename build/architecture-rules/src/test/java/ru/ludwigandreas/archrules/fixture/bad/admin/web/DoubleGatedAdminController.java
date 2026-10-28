package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.admin.AdminAccess;

/** Violates no-second-gate on the method; ClassGatedAdminController does so on the class. */
@RestController
@RequestMapping("/admin/v1/jobs")
public class DoubleGatedAdminController {

    @GetMapping
    @AdminAccess("jobs.read")
    @PreAuthorize("hasRole('ADMIN')")
    public String list() {
        return "jobs";
    }
}

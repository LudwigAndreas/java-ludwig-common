package ru.ludwigandreas.archrules.fixture.bad.spring.catalog.web;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/** Violation: a transaction that spans the HTTP response. */
@RestController
@Transactional
public class TransactionalController {

    @Transactional
    public String update() {
        return "ok";
    }
}

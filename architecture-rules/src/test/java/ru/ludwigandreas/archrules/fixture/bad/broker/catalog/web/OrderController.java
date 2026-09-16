package ru.ludwigandreas.archrules.fixture.bad.broker.catalog.web;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    public String status() {
        return "ok";
    }
}

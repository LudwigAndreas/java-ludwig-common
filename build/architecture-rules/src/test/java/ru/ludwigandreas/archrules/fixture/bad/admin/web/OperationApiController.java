package ru.ludwigandreas.archrules.fixture.bad.admin.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.operation.RequiresOperation;

/** Not administrative: a business operation under /api/v1/, which only no-second-gate concerns. */
@RestController
@RequestMapping("/api/v1/orders")
public class OperationApiController {

    @GetMapping
    @RequiresOperation("sales.order.read")
    public String list() {
        return "orders";
    }
}

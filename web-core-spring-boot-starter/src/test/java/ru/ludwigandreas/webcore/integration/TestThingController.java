package ru.ludwigandreas.webcore.integration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.webcore.web.PageResponse;

/** One endpoint per failure mode the pipeline claims to cover. */
@RestController
@RequestMapping("/test")
@Validated
public class TestThingController {

    static final UUID MISSING_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @GetMapping("/business")
    public String business() {
        throw new ThingNotFoundException(MISSING_ID);
    }

    /** The wrapping a transaction manager or a proxy does on the way out. */
    @GetMapping("/wrapped")
    public String wrapped() {
        throw new IllegalStateException("committed too late", new ThingNotFoundException(MISSING_ID));
    }

    @PostMapping("/things")
    public TestThingRequest create(@Valid @RequestBody TestThingRequest request) {
        return request;
    }

    /** A constraint on the method's own parameter, not on a body. */
    @GetMapping("/search")
    public PageResponse<String> search(@RequestParam @Min(1) int size) {
        return PageResponse.empty(size);
    }

    @GetMapping("/denied")
    public String denied() {
        throw new AccessDeniedException("missing ROLE_EDITOR");
    }

    @GetMapping("/unauthenticated")
    public String unauthenticated() {
        throw new BadCredentialsException("token expired at 12:03");
    }

    @GetMapping("/duplicate")
    public String duplicate() {
        throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
    }

    @GetMapping("/stale")
    public String stale() {
        throw new OptimisticLockingFailureException("row version 3, expected 2");
    }

    @GetMapping("/quota")
    public String quota() {
        throw new QuotaExceededException(50);
    }

    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException("connection pool exhausted at host db-7.internal");
    }

    @GetMapping("/ok")
    public PageResponse<String> ok() {
        return PageResponse.ofAll(List.of("a", "b"));
    }
}

package ru.ludwigandreas.job.core.admin.web;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.job.core.admin.JobAdminOperations;
import ru.ludwigandreas.job.core.admin.service.JobAdminService;
import ru.ludwigandreas.job.core.admin.service.JobAdminViews.JobView;
import ru.ludwigandreas.job.core.admin.service.JobAdminViews.LeaseView;
import ru.ludwigandreas.security.admin.AdminAccess;

/**
 * The {@code jobs} administrative area: scheduled jobs, their run leases, run now, and forced release.
 */
@RestController
@RequestMapping("/admin/v1/jobs")
public class JobAdminController {

    private final JobAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the area's operations
     */
    public JobAdminController(JobAdminService service) {
        this.service = service;
    }

    /**
     * Every scheduled job, with its lease.
     *
     * @return the jobs
     */
    @GetMapping
    @AdminAccess(JobAdminOperations.READ)
    public List<JobView> jobs() {
        return service.jobs();
    }

    /**
     * Every lease row.
     *
     * @return the leases
     */
    @GetMapping("/locks")
    @AdminAccess(JobAdminOperations.READ)
    public List<LeaseView> leases() {
        return service.leases();
    }

    /**
     * Runs a job on the answering replica as soon as it has a thread.
     *
     * @param name the job's name
     * @return {@code 202} with the job, {@code 409} when its lease is held anywhere
     */
    @PostMapping("/{name}/run")
    @AdminAccess(JobAdminOperations.OPERATE)
    public ResponseEntity<JobView> run(@PathVariable String name) {
        return ResponseEntity.accepted().body(service.runSoon(name));
    }

    /**
     * Frees a held lease whatever instance holds it.
     *
     * @param name the lease's name
     * @return the lease as it was before the release; {@code 409} when it was not held
     */
    @PostMapping("/locks/{name}/release")
    @AdminAccess(JobAdminOperations.PRIVILEGED)
    public LeaseView release(@PathVariable String name) {
        return service.forceRelease(name);
    }
}

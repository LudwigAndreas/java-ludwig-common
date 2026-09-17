package ru.ludwigandreas.notification.service.lock;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.repository.DistributedLockRepository;

/**
 * The three transactional operations on a lease, as their own bean.
 *
 * <p>Separate from {@link PostgresDistributedLock} on purpose, and not for tidiness. Spring's
 * proxy-based AOP does not intercept a bean calling its own methods, so a
 * {@code @Transactional tryAcquire()} invoked as {@code this.tryAcquire()} from another method of the
 * same class would run with no transaction at all - and the failure is invisible, because the upsert
 * still executes in its own implicit transaction and appears to work. It would only show up as a
 * lock that occasionally lets two replicas in. The outbox module splits its outcome recorder out for
 * exactly this reason.
 *
 * <p>{@code REQUIRES_NEW} keeps each operation to its own short transaction, so a job that holds the
 * lock for minutes holds no database transaction while it works.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LockLeaseService {

    private final DistributedLockRepository repository;
    private final NotificationProperties properties;

    /** @return whether {@code owner} now holds the lock */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(String lockName, String owner) {
        Instant now = Instant.now();
        String holder = repository.tryAcquire(lockName, owner, now,
                now.plus(properties.getLocks().getLease()));
        return owner.equals(holder);
    }

    /** @return whether {@code owner} still held the lock and the lease was extended */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renew(String lockName, String owner) {
        return repository.renew(lockName, owner, Instant.now().plus(properties.getLocks().getLease())) > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String lockName, String owner) {
        repository.release(lockName, owner);
    }
}

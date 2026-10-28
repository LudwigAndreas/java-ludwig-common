package ru.ludwigandreas.messaging.db.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationContributor;

/**
 * The {@code messaging} area's administrative operations. Only {@code messaging.read} guards an endpoint: moving a
 * consumer's offset needs every replica's consumer paused first, which this release does not offer.
 */
public final class MessagingAdminOperations implements AdminOperationContributor {

    /** See consumer lag per group and partition. */
    public static final String READ = "messaging.read";

    /** Reserved; guards no endpoint yet. */
    public static final String OPERATE = "messaging.operate";

    /** Reserved; guards no endpoint yet. */
    public static final String PRIVILEGED = "messaging.privileged";

    private static final String KEY = "ludwig.messaging-db.admin.";

    @Override
    public Collection<AdminOperation> adminOperations() {
        return List.of(AdminOperation.of(READ, KEY + READ), AdminOperation.of(OPERATE, KEY + OPERATE),
                AdminOperation.of(PRIVILEGED, KEY + PRIVILEGED));
    }
}

package ru.ludwigandreas.grpc.server.interceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import ru.ludwigandreas.grpc.core.context.ThreadBinding;
import ru.ludwigandreas.grpc.server.spi.GrpcCallContextContributor;

/**
 * Every {@link GrpcCallContextContributor} in order, and the thread bindings they declare.
 */
public final class CallContextContributors {

    private final List<GrpcCallContextContributor> contributors;
    private final List<ThreadBinding> bindings;

    /**
     * Orders the contributors.
     *
     * @param contributors the contributor beans, in any order
     */
    public CallContextContributors(List<GrpcCallContextContributor> contributors) {
        List<GrpcCallContextContributor> ordered = new ArrayList<>(contributors);
        AnnotationAwareOrderComparator.sort(ordered);
        this.contributors = List.copyOf(ordered);
        this.bindings = ordered.stream().map(GrpcCallContextContributor::binding).filter(Objects::nonNull).toList();
    }

    List<GrpcCallContextContributor> contributors() {
        return contributors;
    }

    List<ThreadBinding> bindings() {
        return bindings;
    }
}

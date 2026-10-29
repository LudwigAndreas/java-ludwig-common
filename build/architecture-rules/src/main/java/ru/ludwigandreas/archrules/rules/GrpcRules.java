package ru.ludwigandreas.archrules.rules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Set;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * gRPC between platform services goes through the two starters: nobody builds a channel or a server
 * by hand, and a service implementation is a stereotyped, thin adapter.
 *
 * <h2>Why these four rules, and what each one stops</h2>
 *
 * <ol>
 *   <li><b>No hand-built channel or server.</b> A {@code ManagedChannelBuilder} in a service is a
 *       client with no deadline ceiling, no circuit breaker, no audit record and its own idea of
 *       when to retry; a {@code ServerBuilder} is a server whose calls are unauthenticated unless
 *       its author remembered. Both are what {@code rest-client-spring-boot-starter} was written to
 *       stop on the HTTP side, repeated on a second transport.</li>
 *   <li><b>A service implementation carries the stereotype.</b> The server starter registers
 *       services by {@code @LudwigGrpcService}. An implementation without it is either dead code or
 *       is being bound by something other than the starter - and the second is rule 1 in disguise,
 *       reached through {@code addService} on a builder obtained elsewhere.</li>
 *   <li><b>No proxy-based annotation on a service.</b> See below; this is the rule that matters
 *       most.</li>
 *   <li><b>No repository behind a service.</b> The adapter converts and delegates. One that reads a
 *       repository directly has taken the application service's job, and with it the transaction
 *       boundary and the authorization that live there - which rule 3 has just said cannot live
 *       here.</li>
 * </ol>
 *
 * <h2>The proxy trap</h2>
 *
 * <p>{@code @PreAuthorize}, {@code @Secured} and {@code @Transactional} work by wrapping the bean
 * in a proxy and intercepting calls made <em>through</em> it. gRPC does not call through it. At
 * startup the server asks the bean for its {@code ServerServiceDefinition}; the proxy forwards
 * {@code bindService()} to the target, and the definition that comes back holds method handlers
 * bound to the <em>target object</em>. Every call from then on goes straight to the target.
 *
 * <p>So the annotation compiles, the application starts, the bean is proxied, and the annotation is
 * never evaluated. For {@code @Transactional} that is a missing transaction, which usually fails
 * loudly. For {@code @PreAuthorize} it is an authorization rule that is written down, reviewed,
 * approved and not enforced - and nothing fails. That asymmetry is why this is a build failure and
 * not a README paragraph.
 *
 * <p>Two annotations on a service method are honoured, and are not in the refused list:
 * {@code @Idempotent} and {@code @AdminAccess}. Neither works through a proxy - the server starter
 * reads both reflectively when it binds the service and enforces them in its own interceptors, and
 * fails startup when it cannot (no idempotency store; an operation the catalog lacks). An
 * administrative operation is therefore declared on the gRPC method itself, as on an HTTP handler.
 *
 * <p>Annotations are matched by <em>name</em>, as everywhere in this jar, so that a service
 * enabling these rules inherits neither gRPC nor Spring Security from them.
 *
 * <h2>What this group cannot see</h2>
 *
 * <p>Five conventions in this area leave no trace in bytecode. They are listed in this module's
 * README under the group and repeated here because this is the point of the rule:
 *
 * <ol>
 *   <li><b>That a {@code .proto} change is wire-compatible.</b> The generated Java does not record
 *       field numbers in a form a structural rule can compare across releases; renumbering a field
 *       is invisible and renaming one is a false alarm.</li>
 *   <li><b>That a declared {@code idempotency_level} is true.</b> {@code scripts/check_protos.sh}
 *       checks it is declared. Only the method's author knows whether a repeat is safe.</li>
 *   <li><b>That a client's target resolves to every server.</b> A fact about a cluster.</li>
 *   <li><b>That a client's keepalive is no shorter than its server's permitted interval.</b> A
 *       fact about two deployments' configuration.</li>
 *   <li><b>That a call between two platform services uses gRPC rather than REST.</b> Whether a
 *       host is internal is not something a class file says. A rule keyed on it could only match
 *       nothing, stay green, and read as coverage.</li>
 * </ol>
 *
 * <p>The contents and placement of {@code .proto} files are deliberately not here either: a
 * {@code .proto} is a resource, and the gate script owns it.
 */
public final class GrpcRules implements ArchitectureRuleSet {

    /** Nothing outside the two gRPC starters builds a channel or a server. */
    public static final RuleId NO_HAND_BUILT_TRANSPORT =
            RuleId.of(RuleGroup.GRPC, "no-hand-built-channel-or-server");

    /** A gRPC service implementation carries the platform stereotype. */
    public static final RuleId SERVICE_IS_STEREOTYPED =
            RuleId.of(RuleGroup.GRPC, "service-carries-the-stereotype");

    /** A gRPC service carries no annotation that only works through a Spring proxy. */
    public static final RuleId NO_PROXY_ANNOTATIONS_ON_SERVICE =
            RuleId.of(RuleGroup.GRPC, "no-proxy-annotations-on-service");

    /** A gRPC service delegates to an application service, never to a repository. */
    public static final RuleId SERVICE_IS_A_THIN_ADAPTER =
            RuleId.of(RuleGroup.GRPC, "service-is-a-thin-adapter");

    /** The two modules that legitimately assemble a transport. */
    private static final List<String> STARTER_PACKAGES =
            List.of("ru.ludwigandreas.grpc.server..", "ru.ludwigandreas.grpc.client..");

    /** The stereotype, by name; see the class comment for why nothing here is matched by type. */
    private static final String STEREOTYPE = "ru.ludwigandreas.grpc.server.LudwigGrpcService";

    /** What every generated service base class implements. */
    private static final String BINDABLE_SERVICE = "io.grpc.BindableService";

    /**
     * Every entry point through which a channel or a server is assembled.
     *
     * <p>The shaded names are listed beside the plain ones because {@code grpc-netty-shaded}
     * relocates the Netty transport, and a rule that knew only {@code io.grpc.netty} would be
     * bypassed by the very artifact this platform ships.
     */
    private static final Set<String> TRANSPORT_BUILDERS = Set.of(
            "io.grpc.ManagedChannelBuilder",
            "io.grpc.ServerBuilder",
            "io.grpc.Grpc",
            "io.grpc.netty.NettyChannelBuilder",
            "io.grpc.netty.NettyServerBuilder",
            "io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder",
            "io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder",
            "io.grpc.okhttp.OkHttpChannelBuilder",
            "io.grpc.okhttp.OkHttpServerBuilder",
            "io.grpc.inprocess.InProcessChannelBuilder",
            "io.grpc.inprocess.InProcessServerBuilder");

    /** Annotations that take effect only when the call arrives through a Spring proxy. */
    private static final List<String> PROXY_ANNOTATIONS = List.of(
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.prepost.PreFilter",
            "org.springframework.security.access.prepost.PostFilter",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed",
            "jakarta.annotation.security.PermitAll",
            "jakarta.annotation.security.DenyAll",
            "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional",
            "org.springframework.cache.annotation.Cacheable",
            "org.springframework.scheduling.annotation.Async");

    @Override
    public RuleGroup group() {
        return RuleGroup.GRPC;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(
                ArchitectureRule.of(NO_HAND_BUILT_TRANSPORT, noHandBuiltTransport(),
                        "Configure a named client under ludwig.grpc.client.clients and inject its stub with"
                                + " @LudwigGrpcClient, or declare a @LudwigGrpcService, instead of building a"
                                + " channel or a server here. A hand-built channel has no deadline ceiling, no"
                                + " circuit breaker, no audit record and its own idea of when a call may be"
                                + " repeated; a hand-built server is unauthenticated unless its author"
                                + " remembered. Those decisions are taken once, in the two gRPC starters."),
                ArchitectureRule.of(SERVICE_IS_STEREOTYPED, serviceIsStereotyped(),
                        "Annotate the service implementation with @LudwigGrpcService. The server starter"
                                + " registers services by that stereotype, so an implementation without it"
                                + " is either never served or is being bound by something other than the"
                                + " starter - which bypasses its authentication, limits and error mapping."),
                ArchitectureRule.of(NO_PROXY_ANNOTATIONS_ON_SERVICE, noProxyAnnotations(),
                        "Move the annotation to the application service the gRPC service delegates to. gRPC"
                                + " dispatches to the object bindService() captured, not through the Spring"
                                + " proxy around this bean, so @PreAuthorize, @Secured, @Transactional,"
                                + " @Cacheable and @Async here compile, start and are never applied. For an"
                                + " authorization rule that means it is written down and not enforced."
                                + " @AdminAccess and @Idempotent are honoured here: the server starter reads"
                                + " them itself."),
                ArchitectureRule.of(SERVICE_IS_A_THIN_ADAPTER, serviceIsAThinAdapter(context),
                        "Delegate to an application service instead of reading a repository from the gRPC"
                                + " service. The adapter converts messages and nothing else: the transaction"
                                + " boundary and the authorization belong to the service layer, and cannot be"
                                + " declared here - see no-proxy-annotations-on-service; the one exception is"
                                + " an administrative operation, declared with @AdminAccess."));
    }

    /**
     * Nothing outside the two starters depends on a channel or server builder.
     *
     * <p>A dependency on the type rather than a call to one of its factory methods, for the reason
     * {@link CachingRules} gives about Caffeine: the dependency is the thing that cannot be hidden.
     * {@code classes()} and not {@code noClasses()}, also for the reason given there - the
     * condition reports violations only, and {@code never()} would invert it into a rule that
     * reports nothing.
     */
    private static ArchRule noHandBuiltTransport() {
        DescribedPredicate<JavaClass> builders = DescribedPredicate.describe(
                "a gRPC channel or server builder",
                javaClass -> TRANSPORT_BUILDERS.contains(javaClass.getName()));
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingOutsideOf(STARTER_PACKAGES))
                .should(ArchitectureConditions.notDependOnClassesThat(builders,
                                "a gRPC channel or server builder")
                        .as("not build a gRPC channel or server of their own"))
                .as("Only the gRPC starters build a channel or a server");
    }

    /**
     * Every concrete implementation of a generated service base carries the stereotype.
     *
     * <p>Concrete only: the generated {@code XxxImplBase} is itself a {@code BindableService} and
     * is abstract, and a service's own abstract intermediate class is not what gets registered.
     */
    private static ArchRule serviceIsStereotyped() {
        return ArchRuleDefinition.classes()
                .that(ArchitecturePredicates.residingOutsideOf(STARTER_PACKAGES))
                .and(concreteServiceImplementations())
                .should(carryTheStereotype())
                .as("A gRPC service implementation carries @LudwigGrpcService");
    }

    private static ArchRule noProxyAnnotations() {
        return ArchRuleDefinition.classes()
                .that(grpcServices())
                .should(notCarryAProxyAnnotation())
                .as("A gRPC service carries no annotation that only works through a Spring proxy");
    }

    private static ArchRule serviceIsAThinAdapter(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(grpcServices())
                .should(ArchitectureConditions.notDependOnClassesThat(
                                ConventionPredicates.repositories(context), "repositories")
                        .as("not reach a repository directly"))
                .as("A gRPC service delegates to an application service, never to a repository");
    }

    /**
     * A gRPC service, recognised either way it can be one: by the stereotype or by the type.
     *
     * <p>Both, so that removing the stereotype to silence the proxy-annotation rule trips the
     * stereotype rule instead of making the class invisible to this group.
     */
    private static DescribedPredicate<JavaClass> grpcServices() {
        return ArchitecturePredicates.<JavaClass>annotatedWithAny(List.of(STEREOTYPE))
                .or(concreteServiceImplementations())
                .as("gRPC services");
    }

    private static DescribedPredicate<JavaClass> concreteServiceImplementations() {
        return DescribedPredicate.describe("concrete gRPC service implementations",
                javaClass -> !javaClass.isInterface()
                        && !javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
                        && javaClass.isAssignableTo(BINDABLE_SERVICE));
    }

    private static ArchCondition<JavaClass> carryTheStereotype() {
        return new ArchCondition<>("be annotated with @LudwigGrpcService") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean stereotyped = item.isAnnotatedWith(STEREOTYPE) || item.isMetaAnnotatedWith(STEREOTYPE);
                if (!stereotyped) {
                    events.add(SimpleConditionEvent.violated(item, String.format(
                            "%s implements a gRPC service and is not annotated with @LudwigGrpcService in %s",
                            item.getName(), item.getSourceCodeLocation())));
                }
            }
        };
    }

    /**
     * Fails for a proxy annotation on the class or on any of its methods.
     *
     * <p>Written out rather than composed from {@code notBeAnnotatedWithAny} and
     * {@code membersNotAnnotatedWithAny}, so that one violation names the annotation, the member and
     * the reason together - the reader of this failure has an annotation that looks correct and
     * needs to be told why it is not.
     */
    private static ArchCondition<JavaClass> notCarryAProxyAnnotation() {
        return new ArchCondition<>("not carry a proxy-based annotation") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (String annotation : PROXY_ANNOTATIONS) {
                    if (item.isAnnotatedWith(annotation) || item.isMetaAnnotatedWith(annotation)) {
                        events.add(violation(item, item.getName(), annotation));
                    }
                    item.getMethods().stream()
                            .filter(method -> method.isAnnotatedWith(annotation)
                                    || method.isMetaAnnotatedWith(annotation))
                            .forEach(method -> events.add(violation(item, method.getFullName(), annotation)));
                }
            }

            private ConditionEvent violation(JavaClass item, String where, String annotation) {
                return SimpleConditionEvent.violated(item, String.format(
                        "%s is annotated with @%s, which is not applied on the gRPC call path: gRPC"
                                + " dispatches to the target object, not through the Spring proxy, in %s",
                        where, annotation, item.getSourceCodeLocation()));
            }
        };
    }
}

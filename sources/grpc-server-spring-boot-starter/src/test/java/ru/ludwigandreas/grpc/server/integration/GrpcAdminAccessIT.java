package ru.ludwigandreas.grpc.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import ru.ludwigandreas.echo.grpc.v1.EchoReply;
import ru.ludwigandreas.echo.grpc.v1.EchoRequest;
import ru.ludwigandreas.echo.grpc.v1.EchoServiceGrpc;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;
import ru.ludwigandreas.grpc.server.config.GrpcServerAdminAutoConfiguration;
import ru.ludwigandreas.grpc.server.integration.support.AdminEchoService;
import ru.ludwigandreas.grpc.server.integration.support.Calls;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminAccessEvaluator;
import ru.ludwigandreas.security.admin.AdminCallerPolicy;
import ru.ludwigandreas.security.admin.AdminGrants;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.admin.AdminOperationContributor;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * {@code @AdminAccess} on gRPC methods, against the real administrative surface: the same catalog, grants, caller
 * policy and evaluator the HTTP surface uses, in one application serving both.
 */
@SpringBootTest(classes = GrpcAdminAccessIT.AdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "ludwig.grpc.server.in-process-name=" + GrpcAdminAccessIT.SERVER,
            "ludwig.security.jwt.enabled=false",
            "ludwig.web.i18n.configure-validator=false",
            "ludwig.admin.grants.catalog.read[0]=CATALOG_READER",
            "ludwig.admin.grants.catalog.read[1]=CATALOG_OPERATOR",
            "ludwig.admin.grants.catalog.operate[0]=CATALOG_OPERATOR",
            "ludwig.admin.grants.catalog.privileged[0]=CATALOG_ADMIN"})
@AutoConfigureMockMvc
class GrpcAdminAccessIT {

    static final String SERVER = "grpc-admin-it";

    /** Each token subject is a service principal with these roles; {@code partner} is a partner. */
    private static final Map<String, String> ROLES = Map.of(
            "reader", "CATALOG_READER", "operator", "CATALOG_OPERATOR", "admin", "CATALOG_ADMIN",
            "partner", "CATALOG_ADMIN", "nobody", "SOMETHING_ELSE");

    private static ManagedChannel channel;

    @Autowired
    private AdminEchoService service;

    @Autowired
    private List<AuthorizationDeniedEvent<?>> denials;

    @BeforeAll
    static void openChannel() {
        channel = InProcessChannelBuilder.forName(SERVER).build();
    }

    @AfterAll
    static void closeChannel() {
        channel.shutdownNow();
    }

    @BeforeEach
    void forgetDenials() {
        denials.clear();
    }

    private static EchoServiceGrpc.EchoServiceBlockingStub as(String subject, String... metadata) {
        String[] pairs = new String[metadata.length + 2];
        pairs[0] = "authorization";
        pairs[1] = "Bearer valid-" + subject;
        System.arraycopy(metadata, 0, pairs, 2, metadata.length);
        return Calls.stub(channel, pairs);
    }

    private static Status.Code refusal(Runnable call) {
        return catchThrowableOfType(call::run, StatusRuntimeException.class).getStatus().getCode();
    }

    /** What the audit listener records as the action: the refused method's name, whichever transport. */
    private List<String> deniedActions() {
        return denials.stream().map(AuthorizationDeniedEvent::getObject).map(object -> object instanceof Method method
                ? method.getName() : ((HandlerMethod) object).getMethod().getName()).toList();
    }

    @Test
    @DisplayName("automation granted the operation runs the handler, with the invocation bound for its audit")
    void granted() {
        assertThat(as("operator").store(Calls.text("x")).getText()).isEqualTo("catalog.operate|null");
        assertThat(service.invoked("store")).isPositive();
    }

    @Test
    @DisplayName("a caller without the operation is PERMISSION_DENIED, the handler does not run, the denial is audited")
    void ungranted() {
        int before = service.invoked("store");

        assertThat(refusal(() -> as("reader").store(Calls.text("x")))).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(service.invoked("store")).isEqualTo(before);
        assertThat(deniedActions()).containsExactly("store");
    }

    @Test
    @DisplayName("a privileged call needs admin-reason metadata: INVALID_ARGUMENT without it, the reason bound with it")
    void privilegedNeedsAReason() {
        int before = service.invoked("append");

        assertThat(refusal(() -> as("admin").append(Calls.text("x")))).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(service.invoked("append")).isEqualTo(before);
        assertThat(as("admin", "admin-reason", "INC-7").append(Calls.text("x")).getText())
                .isEqualTo("catalog.privileged|INC-7");
    }

    @Test
    @DisplayName("a partner is refused whatever it holds, and an unannotated method is untouched")
    void partnerAndUnannotated() {
        assertThat(refusal(() -> as("partner").append(Calls.text("x")))).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(as("partner").stream(EchoRequest.newBuilder().setText("open").setRepeat(1).build()).next()
                .getText()).isEqualTo("open");
    }

    @Nested
    class CrossTransport {

        @Autowired
        private MockMvc mvc;

        @Test
        @DisplayName("one grant serves HTTP and gRPC alike, and a refusal is the same audited action on both")
        void sameGrantBothTransports() throws Exception {
            assertThat(mvc.perform(get("/admin/v1/catalog/echo").with(principal("reader"))).andReturn()
                    .getResponse().getStatus()).isEqualTo(200);
            assertThat(as("reader").echo(Calls.text("x")).getText()).isEqualTo("catalog.read|null");
            assertThat(denials).isEmpty();

            assertThat(mvc.perform(get("/admin/v1/catalog/echo").with(principal("nobody"))).andReturn()
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(refusal(() -> as("nobody").echo(Calls.text("x")))).isEqualTo(Status.Code.PERMISSION_DENIED);
            assertThat(deniedActions()).containsExactly("echo", "echo");
        }

        private static org.springframework.test.web.servlet.request.RequestPostProcessor principal(String subject) {
            return TestPrincipalBuilder.service(subject).roles(ROLES.get(subject)).postProcessor();
        }
    }

    @Nested
    class Startup {

        private final ApplicationContextRunner runner = GrpcServerLifecycleIT.RUNNER
                .withConfiguration(AutoConfigurations.of(GrpcServerAdminAutoConfiguration.class))
                .withUserConfiguration(TypoService.class);

        @Test
        @DisplayName("an operation the catalog lacks fails startup, naming the method and the operation")
        void typo() {
            runner.withUserConfiguration(Surface.class).run(context -> assertThat(context).hasFailed()
                    .getFailure().hasStackTraceContaining("ludwig.echo.v1.EchoService/Echo")
                    .hasStackTraceContaining("catalgo.read"));
        }

        @Test
        @DisplayName("an annotated method with no administrative surface fails startup instead of serving anyone")
        void noSurface() {
            runner.run(context -> assertThat(context).hasFailed()
                    .getFailure().hasStackTraceContaining("ludwig.echo.v1.EchoService/Echo"));
        }
    }

    /** {@code catalog} misspelt. */
    @LudwigGrpcService
    static class TypoService extends EchoServiceGrpc.EchoServiceImplBase {

        @Override
        @AdminAccess("catalgo.read")
        public void echo(EchoRequest request, StreamObserver<EchoReply> observer) {
            observer.onCompleted();
        }
    }

    /** The administrative surface's beans, by hand: the startup check needs nothing else. */
    @Configuration(proxyBeanMethods = false)
    static class Surface {

        @Bean
        AdminOperationCatalog catalog() {
            return new AdminOperationCatalog(List.of(() -> List.of(AdminOperation.of("catalog.read", "k"),
                    AdminOperation.of("catalog.operate", "k"), AdminOperation.of("catalog.privileged", "k"))));
        }

        @Bean
        AdminAccessEvaluator evaluator(AdminOperationCatalog catalog, ApplicationEventPublisher events) {
            return new AdminAccessEvaluator(new AdminGrants(Map.of(), catalog),
                    new SpringAuthorizationEventPublisher(events));
        }

        @Bean
        AdminCallerPolicy callers(AdminAccessEvaluator evaluator) {
            return new AdminCallerPolicy(evaluator, false, Set.of());
        }
    }

    /** An HTTP administrative endpoint on the same operation as the gRPC {@code Echo}. */
    @RestController
    static class CatalogAdminController {

        @GetMapping("/admin/v1/catalog/echo")
        @AdminAccess("catalog.read")
        public String echo() {
            return "echo";
        }
    }

    /** No database: the test classpath carries db-core and the idempotency store, neither under test here. */
    // Not @SpringBootConfiguration: EchoApplication is this package's one, which the other ITs discover.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "ru.ludwigandreas.db.core.config.DatabaseAutoConfiguration"})
    static class AdminApplication {

        @Bean
        AdminEchoService adminEchoService() {
            return new AdminEchoService();
        }

        @Bean
        CatalogAdminController catalogAdminController() {
            return new CatalogAdminController();
        }

        @Bean
        AdminOperationContributor catalogOperations() {
            return () -> List.of(
                    AdminOperation.of("catalog.read", "test.catalog.read"),
                    AdminOperation.of("catalog.operate", "test.catalog.operate"),
                    AdminOperation.of("catalog.privileged", "test.catalog.privileged"));
        }

        @Bean
        ProblemMessageBundle adminEchoMessages() {
            return ProblemMessageBundle.of("i18n/admin-echo-messages");
        }

        @Bean
        List<AuthorizationDeniedEvent<?>> denials() {
            return new CopyOnWriteArrayList<>();
        }

        @Bean
        ApplicationListener<AuthorizationDeniedEvent<?>> recordDenials(List<AuthorizationDeniedEvent<?>> denials) {
            return denials::add;
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                String subject = token.startsWith("valid-") ? token.substring("valid-".length()) : null;
                if (subject == null || !ROLES.containsKey(subject)) {
                    throw new BadJwtException("not a test token");
                }
                return Jwt.withTokenValue(token).header("alg", "none").subject(subject).build();
            };
        }

        @Bean
        Converter<Jwt, AbstractAuthenticationToken> jwtConverter() {
            // A class, not a lambda: MVC registers every Converter bean and needs its type arguments.
            return new Converter<Jwt, AbstractAuthenticationToken>() {
                @Override
                public AbstractAuthenticationToken convert(Jwt jwt) {
                    return ("partner".equals(jwt.getSubject())
                            ? TestPrincipalBuilder.partner(jwt.getSubject())
                            : TestPrincipalBuilder.service(jwt.getSubject())).roles(ROLES.get(jwt.getSubject()))
                            .authentication();
                }
            };
        }
    }
}

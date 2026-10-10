package ru.ludwigandreas.testsupport.local;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationHook;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.ThrowingConsumer;
import org.testcontainers.containers.GenericContainer;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.container.KafkaPropertiesInitializer;
import ru.ludwigandreas.testsupport.container.LocalStackS3Initializer;
import ru.ludwigandreas.testsupport.local.mail.LocalMailbox;
import ru.ludwigandreas.testsupport.local.stub.StubCatalog;
import ru.ludwigandreas.testsupport.local.stub.StubServersInitializer;

/**
 * Starts a service's real entry point on a developer machine, with what it needs to be debugged.
 *
 * <pre>{@code
 * // src/test/java/.../local/LocalCatalogApplication.java
 * public final class LocalCatalogApplication {
 *
 *     public static void main(String[] args) {
 *         LocalRun.from(CatalogApplication::main)
 *                 .postgres()
 *                 .kafka()
 *                 .stubs(new CatalogStubs())
 *                 .fixtures(SupplierFixture.class, ProductFixture.class)
 *                 .run(args);
 *     }
 * }
 * }</pre>
 *
 * <p>Run it from the IDE with a breakpoint set, or with
 * {@code mvn -pl :<service> spring-boot:test-run}.
 *
 * <h2>It starts the service's own main</h2>
 *
 * <p>The application class is not replaced, subclassed or re-declared. The service's {@code main}
 * runs as written and this class adds to the {@code SpringApplication} it creates, so the bean graph
 * under the breakpoint is the one production builds, plus the local additions.
 *
 * <h2>Nothing is inferred and no profile is imposed</h2>
 *
 * <p>A backing service starts only when it is named here. No Spring profile is activated unless
 * {@link #profiles(String...)} names it: a service's existing {@code local} profile may well switch
 * off the very things this launcher turns on, and which profile a local run wants is the service's
 * decision.
 *
 * <h2>Why this uses {@code SpringApplication.withHook} and not {@code SpringApplication.from}</h2>
 *
 * <p>{@code from(...).with(...)} can add configuration classes and profiles but not an
 * {@code ApplicationContextInitializer}, and the partner stubs have to be one: their addresses must be
 * in the environment before the rest-client starter binds them. {@code withHook} is the mechanism
 * {@code from} is itself built on.
 *
 * <h2>This class belongs in {@code src/test}</h2>
 *
 * <p>The module is a test-scoped dependency, so a class that uses this does not compile from
 * {@code src/main}; {@code ludwig-service-parent} additionally fails a service that declares the
 * module at any other scope. A local stand-in cannot reach an image.
 */
public final class LocalRun {

    private static final String PLAN_BEAN_NAME = "ludwigLocalRunPlan";
    private static final String MAILBOX_BEAN_NAME = "ludwigLocalMailbox";
    private static final String MAIL_SERVER_CLASS = "com.icegreen.greenmail.util.GreenMail";

    private final ThrowingConsumer<String[]> main;
    private final Set<BackingService> services = EnumSet.noneOf(BackingService.class);
    private final Set<Class<?>> sources = new LinkedHashSet<>();
    private final Set<String> profiles = new LinkedHashSet<>();
    private StubCatalog catalog;
    private boolean mail;

    private LocalRun(ThrowingConsumer<String[]> main) {
        this.main = main;
    }

    /**
     * Begins a local run of a service.
     *
     * @param main the service's own entry point, as a method reference
     * @return the launcher, to be configured and {@linkplain #run(String...) run}
     */
    public static LocalRun from(ThrowingConsumer<String[]> main) {
        return new LocalRun(main);
    }

    /**
     * Names PostgreSQL. The service's migrations run against it as they would anywhere.
     *
     * @return this launcher
     */
    public LocalRun postgres() {
        services.add(BackingService.POSTGRES);
        return this;
    }

    /**
     * Names Kafka.
     *
     * @return this launcher
     */
    public LocalRun kafka() {
        services.add(BackingService.KAFKA);
        return this;
    }

    /**
     * Names LocalStack S3.
     *
     * @return this launcher
     */
    public LocalRun localstack() {
        services.add(BackingService.LOCALSTACK);
        return this;
    }

    /**
     * Names Redis.
     *
     * @return this launcher
     */
    public LocalRun redis() {
        services.add(BackingService.REDIS);
        return this;
    }

    /**
     * Names a mail server: an in-process one on loopback, published as {@code spring.mail.host} and
     * {@code spring.mail.port}. What the service sends is readable from {@code LocalMailbox}, and
     * over IMAP at the address the start-up summary prints.
     *
     * @return this launcher
     */
    public LocalRun mail() {
        this.mail = true;
        return this;
    }

    /**
     * Stands the service's partners in.
     *
     * <p>With the rest-client starter present, every rest client then needs a stub or a pass-through
     * declaration in this catalog, and the application refuses to start otherwise.
     *
     * @param stubCatalog the service's stubs
     * @return this launcher
     */
    public LocalRun stubs(StubCatalog stubCatalog) {
        this.catalog = stubCatalog;
        return this;
    }

    /**
     * Registers seed fixtures, which run after the service's migrations and before it is ready.
     *
     * @param fixtureTypes fixture classes, or classes that {@code @Import} them
     * @return this launcher
     */
    public LocalRun fixtures(Class<?>... fixtureTypes) {
        sources.addAll(List.of(fixtureTypes));
        return this;
    }

    /**
     * Activates Spring profiles, in addition to any the service's main activates itself.
     *
     * @param names the profiles to add
     * @return this launcher
     */
    public LocalRun profiles(String... names) {
        profiles.addAll(List.of(names));
        return this;
    }

    /**
     * Starts the service.
     *
     * @param args the arguments passed on to the service's main
     * @return the started application context
     * @throws IllegalStateException when a named backing service's library is missing, a rest client
     *                               has neither a stub nor a pass-through, or a fixture fails
     */
    public ConfigurableApplicationContext run(String... args) {
        requireLibraries();
        StubServersInitializer stubs = catalog == null ? null : new StubServersInitializer(catalog);
        List<AutoCloseable> opened = new ArrayList<>();
        AtomicReference<ConfigurableApplicationContext> started = new AtomicReference<>();
        boolean running = false;
        try {
            SpringApplication.withHook(hook(stubs, opened, started), () -> main.accept(args));
            ConfigurableApplicationContext context = started.get();
            if (context == null) {
                throw new IllegalStateException("The main method given to LocalRun.from(...) returned "
                        + "without starting a SpringApplication.");
            }
            running = true;
            return context;
        } finally {
            // A context that fails during refresh never publishes the close event the stubs and
            // the mail server listen for, and their ports would otherwise be held until the JVM exits.
            if (!running) {
                if (stubs != null) {
                    stubs.close();
                }
                opened.forEach(LocalRun::closeQuietly);
            }
        }
    }

    /**
     * Fails before any container starts when a named service's library is not on the classpath.
     *
     * <p>Without this the failure is a {@code NoClassDefFoundError} from inside a static initializer,
     * after PostgreSQL has already spent its seconds starting, naming a class and not the dependency.
     */
    private void requireLibraries() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (mail && !ClassUtils.isPresent(MAIL_SERVER_CLASS, classLoader)) {
            throw new IllegalStateException("The local application names a mail server, but "
                    + MAIL_SERVER_CLASS + " is not on the test classpath. Add "
                    + "com.icegreen:greenmail-junit5 at test scope.");
        }
        for (BackingService service : services) {
            if (!ClassUtils.isPresent(service.requiredClass(), classLoader)) {
                throw new IllegalStateException("The local application names " + service + ", but "
                        + service.requiredClass() + " is not on the test classpath. Add "
                        + service.dependency() + " at test scope.");
            }
        }
    }

    private SpringApplicationHook hook(StubServersInitializer stubs, List<AutoCloseable> opened,
                                       AtomicReference<ConfigurableApplicationContext> started) {
        // Only the first SpringApplication the main creates is the service. A main that builds a
        // parent or bootstrap context as well must not get a second set of stubs.
        AtomicBoolean applied = new AtomicBoolean();
        return application -> {
            if (!applied.compareAndSet(false, true)) {
                return null;
            }
            application.addPrimarySources(primarySources());
            Set<String> merged = new LinkedHashSet<>(application.getAdditionalProfiles());
            merged.addAll(profiles);
            application.setAdditionalProfiles(merged.toArray(String[]::new));
            initializers(stubs, opened).forEach(application::addInitializers);
            return new SpringApplicationRunListener() {
                @Override
                public void contextPrepared(ConfigurableApplicationContext context) {
                    started.set(context);
                }
            };
        };
    }

    private Set<Class<?>> primarySources() {
        Set<Class<?>> all = new LinkedHashSet<>();
        all.add(LocalRunConfiguration.class);
        if (services.contains(BackingService.POSTGRES)) {
            all.add(LocalPostgresConfiguration.class);
        }
        all.addAll(sources);
        return all;
    }

    private List<ApplicationContextInitializer<ConfigurableApplicationContext>> initializers(
            StubServersInitializer stubs, List<AutoCloseable> opened) {
        List<ApplicationContextInitializer<ConfigurableApplicationContext>> all = new ArrayList<>();
        LocalRunPlan plan = new LocalRunPlan(services);
        all.add(context -> context.getBeanFactory().registerSingleton(PLAN_BEAN_NAME, plan));
        if (services.contains(BackingService.KAFKA)) {
            all.add(new KafkaPropertiesInitializer());
        }
        if (services.contains(BackingService.LOCALSTACK)) {
            all.add(new LocalStackS3Initializer());
        }
        if (services.contains(BackingService.REDIS)) {
            all.add(LocalRun::publishRedis);
        }
        if (mail) {
            all.add(context -> publishMail(context, opened));
        }
        if (stubs != null) {
            all.add(stubs);
        }
        return all;
    }

    /**
     * Starts the mail server and points the service's mail sender at it.
     *
     * <p>Kept in a method of its own, reached only when mail was named, so that this class loads in a
     * service that does not have the mail library.
     */
    private static void publishMail(ConfigurableApplicationContext context, List<AutoCloseable> opened) {
        LocalMailbox mailbox = LocalMailbox.start();
        opened.add(mailbox);
        TestPropertyValues.of(
                        "spring.mail.host=" + mailbox.host(),
                        "spring.mail.port=" + mailbox.smtpPort(),
                        "spring.mail.properties.mail.smtp.auth=false",
                        "spring.mail.properties.mail.smtp.starttls.enable=false",
                        "spring.mail.properties.mail.smtp.starttls.required=false")
                .applyTo(context);
        context.getBeanFactory().registerSingleton(MAILBOX_BEAN_NAME, mailbox);
        context.addApplicationListener((ApplicationListener<ContextClosedEvent>) event -> mailbox.close());
    }

    // CHECKSTYLE.OFF: IllegalCatch - AutoCloseable.close() declares Exception, and a failure to
    // release one resource after a failed start must not hide the failure that caused it.
    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // The start-up failure already on its way out is the one worth reporting.
        }
    }
    // CHECKSTYLE.ON: IllegalCatch

    private static void publishRedis(ConfigurableApplicationContext context) {
        GenericContainer<?> redis = Containers.redis();
        TestPropertyValues.of(
                        "spring.data.redis.host=" + redis.getHost(),
                        "spring.data.redis.port=" + redis.getMappedPort(Containers.REDIS_PORT))
                .applyTo(context);
    }
}

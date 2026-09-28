package ru.ludwigandreas.testsupport.fixture;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;

/**
 * A real SMTP server, in-process, for tests that assert on what was actually sent.
 *
 * <h2>Why a real server and not a mocked {@code JavaMailSender}</h2>
 *
 * <p>This reasoning came from {@code notification-service} and must not be lost in the move. A mock
 * proves that a method was called. A real SMTP conversation proves the message is well-formed,
 * actually multipart, and carries the headers a mailbox provider judges it on - and it is the only way
 * to assert that the plain-text alternative exists, which is the part that silently disappears.
 *
 * <h2>Why in-process and not a container</h2>
 *
 * <p>GreenMail is a JUnit 5 extension, not an image, so there is nothing to pin and nothing to start.
 * It is faster than a container and needs no Docker, which means a module can assert on outgoing mail
 * without acquiring a container dependency. That is why it lives here as a fixture rather than in
 * {@link ru.ludwigandreas.testsupport.container.Containers}.
 *
 * <h2>Why per-method lifecycle is the default</h2>
 *
 * <p>{@code withPerMethodLifecycle(true)} so one test's messages never leak into another's assertions
 * - the usual way an email test passes for the wrong reason.
 *
 * <h2>Use</h2>
 *
 * <pre>{@code
 * @RegisterExtension
 * static GreenMailExtension greenMail = GreenMailFixture.smtp();
 * }</pre>
 *
 * <p>The server listens on GreenMail's standard test offset, so the context needs
 * {@code spring.mail.host=127.0.0.1} and {@code spring.mail.port=3025}. Those are set for you by
 * {@link ru.ludwigandreas.testsupport.junit.LudwigPostgresTest}'s sibling property defaults only when
 * a module opts in; a module that boots its own mail configuration sets them itself, because a fixture
 * silently rewriting a service's mail host would be worse than a line of configuration.
 */
public final class GreenMailFixture {

    /**
     * The port GreenMail's {@code ServerSetupTest.SMTP} listens on: the standard test offset, 25 + 3000.
     *
     * <p>Exposed as a constant so a test's property block can reference it rather than repeat the
     * number, and so the relationship to the offset is written down somewhere.
     */
    public static final int SMTP_PORT = 3025;

    private GreenMailFixture() {
    }

    /**
     * An SMTP-only server on the standard test offset, reset between test methods.
     *
     * @return an extension to register with {@code @RegisterExtension}
     */
    public static GreenMailExtension smtp() {
        return new GreenMailExtension(ServerSetupTest.SMTP).withPerMethodLifecycle(true);
    }

    /**
     * An SMTP-only server that keeps its mailboxes for the whole test class.
     *
     * <p>For the rare test whose assertion spans several methods in order. Prefer {@link #smtp()}:
     * a shared mailbox makes a test depend on what ran before it.
     *
     * @return an extension to register with {@code @RegisterExtension}
     */
    public static GreenMailExtension smtpPerClass() {
        return new GreenMailExtension(ServerSetupTest.SMTP).withPerMethodLifecycle(false);
    }
}

package ru.ludwigandreas.testsupport.local.mail;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import ru.ludwigandreas.testsupport.local.SummaryContributor;

/**
 * The local application's mail server, and what it has received.
 *
 * <p>An in-process GreenMail listening on loopback for SMTP and IMAP, on ports the operating system
 * allocates. The service sends to it through its ordinary {@code JavaMailSender}; nothing leaves the
 * machine. A test reads {@link #received()}; a developer points a mail client at the IMAP address the
 * start-up summary prints and reads the messages there.
 *
 * <h2>Why not {@code GreenMailFixture}</h2>
 *
 * <p>{@code test-support}'s fixture is a JUnit extension on fixed port 3025: it has no lifecycle
 * outside a test, and a fixed port means a second local application cannot start. This is the same
 * library with the launcher's lifecycle and an allocated port.
 *
 * <h2>Authentication is off</h2>
 *
 * <p>Any user name and password is accepted, over SMTP and IMAP alike, and a mailbox comes into being
 * when its address first receives a message. Reading one is therefore "log in as the recipient's
 * address with any password".
 */
public final class LocalMailbox implements SummaryContributor, AutoCloseable {

    private static final String LOOPBACK = "127.0.0.1";

    private final GreenMail greenMail;

    private LocalMailbox(GreenMail greenMail) {
        this.greenMail = greenMail;
    }

    /**
     * Starts the mail server.
     *
     * @return the running mailbox
     */
    public static LocalMailbox start() {
        GreenMail greenMail = new GreenMail(new ServerSetup[] {
                new ServerSetup(0, LOOPBACK, ServerSetup.PROTOCOL_SMTP).dynamicPort(),
                new ServerSetup(0, LOOPBACK, ServerSetup.PROTOCOL_IMAP).dynamicPort()});
        greenMail.withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());
        greenMail.start();
        return new LocalMailbox(greenMail);
    }

    /**
     * The host both protocols listen on.
     *
     * @return the loopback address
     */
    public String host() {
        return LOOPBACK;
    }

    /**
     * The port the service sends to.
     *
     * @return the SMTP port
     */
    public int smtpPort() {
        return greenMail.getSmtp().getPort();
    }

    /**
     * The port a mail client reads from.
     *
     * @return the IMAP port
     */
    public int imapPort() {
        return greenMail.getImap().getPort();
    }

    /**
     * Every message received since start, or since the last {@link #clear()}, in arrival order.
     *
     * @return the received messages
     */
    public List<MimeMessage> received() {
        return List.of(greenMail.getReceivedMessages());
    }

    /** Discards every received message, so a test starts from an empty mailbox. */
    public void clear() {
        try {
            greenMail.purgeEmailFromAllMailboxes();
        } catch (FolderException ex) {
            throw new IllegalStateException("Could not empty the local mailboxes", ex);
        }
    }

    @Override
    public String heading() {
        return "Mail";
    }

    @Override
    public List<String> lines() {
        return List.of(
                "smtp          " + LOOPBACK + ":" + smtpPort() + "   (the service sends here)",
                "imap          " + LOOPBACK + ":" + imapPort()
                        + "   (log in as the recipient's address, any password, no TLS)");
    }

    /** Stops the mail server. Safe to call more than once. */
    @Override
    public void close() {
        greenMail.stop();
    }
}

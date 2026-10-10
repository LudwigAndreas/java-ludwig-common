package ru.ludwigandreas.testsupport.local.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import ru.ludwigandreas.testsupport.local.LocalRun;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleApplication;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;
import ru.ludwigandreas.testsupport.local.mail.LocalMailbox;

/**
 * Proves the local mail server receives what the service's own mail sender sends, and that both ways
 * of reading it work: from code, and from a mail client at the address the summary prints.
 */
@ExtendWith(OutputCaptureExtension.class)
class LocalMailIT {

    private static final String RECIPIENT = "ada@example.test";

    @Test
    @DisplayName("mail sent through the service's JavaMailSender is readable from code and over IMAP")
    void receivesWhatTheServiceSends(CapturedOutput output) throws MessagingException {
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .mail()
                .stubs(new SampleStubs())
                .run()) {
            LocalMailbox mailbox = context.getBean(LocalMailbox.class);
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("no-reply@example.test");
            message.setTo(RECIPIENT);
            message.setSubject("Your widget is ready");
            message.setText("alpha");

            context.getBean(JavaMailSender.class).send(message);

            assertThat(mailbox.received()).hasSize(1);
            MimeMessage received = mailbox.received().get(0);
            assertThat(received.getSubject()).isEqualTo("Your widget is ready");
            assertThat(received.getAllRecipients()[0]).hasToString(RECIPIENT);

            assertThat(output)
                    .contains("smtp          127.0.0.1:" + mailbox.smtpPort())
                    .contains("imap          127.0.0.1:" + mailbox.imapPort());
            assertThat(subjectsOverImap(mailbox, RECIPIENT)).containsExactly("Your widget is ready");

            mailbox.clear();
            assertThat(mailbox.received()).isEmpty();
        }
    }

    @Test
    @DisplayName("an application that does not name mail gets no mail server and no mail properties")
    void notNamedMeansNotStarted() {
        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .run()) {
            assertThat(context.getEnvironment().getProperty("spring.mail.host")).isNull();
            assertThat(context.getBeanProvider(LocalMailbox.class).getIfAvailable()).isNull();
        }
    }

    @Test
    @DisplayName("naming mail without the mail library fails before the service's main is reached")
    void missingLibraryFailsFirst() {
        AtomicBoolean mainCalled = new AtomicBoolean();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new FilteredClassLoader("com.icegreen.greenmail"));
        try {
            assertThatThrownBy(() -> LocalRun.from(args -> mainCalled.set(true)).mail().run())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("com.icegreen:greenmail-junit5");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        assertThat(mainCalled).isFalse();
    }

    /** Reads the inbox the way the summary tells a developer to: the recipient's address, any password. */
    private static java.util.List<String> subjectsOverImap(LocalMailbox mailbox, String address)
            throws MessagingException {
        Session session = Session.getInstance(new Properties());
        try (Store store = session.getStore("imap")) {
            store.connect(mailbox.host(), mailbox.imapPort(), address, "anything");
            try (Folder inbox = store.getFolder("INBOX")) {
                inbox.open(Folder.READ_ONLY);
                java.util.List<String> subjects = new java.util.ArrayList<>();
                for (Message message : inbox.getMessages()) {
                    subjects.add(message.getSubject());
                }
                return subjects;
            }
        }
    }
}

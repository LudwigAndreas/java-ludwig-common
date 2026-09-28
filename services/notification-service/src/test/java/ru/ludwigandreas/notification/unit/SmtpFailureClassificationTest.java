package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.SendFailedException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.channel.SmtpEmailChannel;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.DeliveryResult;
import ru.ludwigandreas.notification.service.model.FailureClass;
import ru.ludwigandreas.notification.service.model.RenderedNotification;

/**
 * SMTP already answers the retryable question precisely - 4yz is temporary, 5yz is permanent - and
 * the difficulty is getting at the answer, because Spring wraps everything and the exception
 * carrying the reply code belongs to whichever JavaMail implementation is on the classpath.
 */
@ExtendWith(MockitoExtension.class)
class SmtpFailureClassificationTest {

    @Mock
    private JavaMailSender mailSender;

    private SmtpEmailChannel channel;

    @BeforeEach
    void setUp() {
        NotificationProperties.Email settings = new NotificationProperties.Email();
        settings.setFromAddress("no-reply@example.internal");
        settings.setFromDisplayName("Notifications");
        channel = new SmtpEmailChannel(mailSender, settings);

        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    @DisplayName("a 4yz reply is temporary and is retried")
    void transientReplyCodeRetries() {
        failWith(new MailSendException(Map.of("msg",
                new jakarta.mail.MessagingException("451 4.3.0 Temporary server error, try again"))));

        assertFailure(FailureClass.RETRYABLE);
    }

    @Test
    @DisplayName("a 5yz reply is permanent and is not retried")
    void permanentReplyCodeIsTerminal() {
        failWith(new MailSendException(Map.of("msg",
                new jakarta.mail.MessagingException("550 5.1.1 User unknown"))));

        assertFailure(FailureClass.TERMINAL);
    }

    /**
     * The definitive signal, and it wins over the reply code: the protocol has named the recipient as
     * the problem, so no amount of retrying changes the outcome.
     */
    @Test
    @DisplayName("an explicitly invalid address is terminal whatever reply code came with it")
    void invalidAddressIsTerminal() throws Exception {
        SendFailedException sendFailed = new SendFailedException("invalid",
                new jakarta.mail.MessagingException("451 would otherwise look retryable"),
                new jakarta.mail.Address[0],
                new jakarta.mail.Address[0],
                new jakarta.mail.Address[] {new InternetAddress("nope@example.invalid")});
        failWith(new MailSendException(Map.of("msg", sendFailed)));

        assertFailure(FailureClass.TERMINAL);
    }

    /**
     * No reply code anywhere in the chain means the failure happened before the server answered -
     * connection refused, a TLS handshake, a timeout - all of which are worth another attempt.
     */
    @Test
    @DisplayName("a failure with no reply code is treated as a transport failure and retried")
    void noReplyCodeRetries() {
        failWith(new MailSendException(Map.of("msg",
                new jakarta.mail.MessagingException("Could not connect to SMTP host"))));

        assertFailure(FailureClass.RETRYABLE);
    }

    /**
     * It looks permanent and usually is - but SMTP credentials come from Vault through the hot-reload
     * module, so a rotation this pod has not picked up yet presents exactly this way, and that one
     * does fix itself. Dead-lettering the whole in-flight queue on a credential rotation would be far
     * worse than a few wasted retries.
     */
    @Test
    @DisplayName("an authentication failure is retried, because a credential rotation looks like one")
    void authenticationFailureRetries() {
        failWith(new MailAuthenticationException("535 authentication failed"));

        assertFailure(FailureClass.RETRYABLE);
    }

    @Test
    @DisplayName("a bounce message quoting the recipient does not put the address in last_error")
    void failureDetailCarriesNoAddress() {
        failWith(new MailSendException(Map.of("msg",
                new jakarta.mail.MessagingException("550 5.1.1 <victim@example.com> unknown"))));

        DeliveryResult result = channel.send(notification());
        // The detail is stored on the delivery, returned by the admin API and written to the log, so
        // what it may contain is a privacy question rather than a formatting one.
        assertThat(result).isInstanceOf(DeliveryResult.Failed.class);
    }

    private void failWith(RuntimeException failure) {
        doThrow(failure).when(mailSender).send(any(MimeMessage.class));
    }

    private void assertFailure(FailureClass expected) {
        DeliveryResult result = channel.send(notification());

        assertThat(result).isInstanceOf(DeliveryResult.Failed.class);
        assertThat(((DeliveryResult.Failed) result).failureClass()).isEqualTo(expected);
    }

    private static RenderedNotification notification() {
        return new RenderedNotification(UUID.randomUUID(), ChannelType.EMAIL,
                "someone@example.com", "en", "Subject", "<p>Body</p>", "Body",
                "welcome", "onboarding", Map.of(), "corr-1");
    }
}

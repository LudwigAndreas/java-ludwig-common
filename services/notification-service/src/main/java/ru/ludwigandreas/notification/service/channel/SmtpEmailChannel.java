package ru.ludwigandreas.notification.service.channel;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.Pii;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.DeliveryResult;
import ru.ludwigandreas.notification.service.model.FailureClass;
import ru.ludwigandreas.notification.service.model.RenderedNotification;

/**
 * Sends over SMTP, as a real multipart message.
 *
 * <h2>Multipart, always</h2>
 *
 * <p>{@code multipart/alternative} with a plain-text part first and the HTML second. The order is
 * part of the format, not a preference: a client picks the last part it understands, so putting HTML
 * first means a text-only client shows the HTML source. And a message with no text alternative at
 * all scores badly with every mailbox provider's spam filter, which is a cost paid by every other
 * recipient on the same sending domain.
 *
 * <h2>Classifying an SMTP failure</h2>
 *
 * <p>SMTP already answers the retryable question and answers it precisely: a 4yz reply is a
 * temporary negative and a 5yz is permanent. The problem is getting at it. Spring wraps everything in
 * {@link MailSendException}, and the concrete exception carrying the reply code belongs to whichever
 * JavaMail implementation is on the classpath - Angus today, something else after the next Boot
 * upgrade. So the code is recovered in two steps that do not depend on any implementation type: the
 * definitive signal first ({@link SendFailedException#getInvalidAddresses()}, which is the protocol
 * telling us the address itself was rejected), then the reply code scraped out of the message text.
 *
 * <p>An authentication failure is deliberately <em>retryable</em>. It looks permanent and usually is,
 * but SMTP credentials in this deployment come from Vault through the hot-reload module, so a
 * rotation that this pod has not picked up yet presents exactly as an auth failure - and that one
 * does fix itself. Dead-lettering the whole in-flight queue on a credential rotation would be a far
 * worse outcome than a few wasted retries.
 */
@Slf4j
public class SmtpEmailChannel implements NotificationChannel {

    /**
     * An SMTP reply code inside an exception message.
     *
     * <p>Deliberately not anchored to the start of the string: the code arrives wrapped. JavaMail's
     * {@code MessagingException.toString()} prefixes it with the exception's class name and a colon,
     * and Spring's {@link MailSendException} concatenates several of those into one line - so an
     * anchored pattern matches nothing at all, and every permanent rejection would be classified as
     * retryable and attempted eight times.
     *
     * <p>What it does require is the shape of a reply rather than any three digits: a 4 or a 5
     * followed by two digits, at the start of a line or after a colon or whitespace, and followed by
     * a space or the hyphen that marks an SMTP multiline continuation. That excludes an order number
     * or a timestamp inside the human-readable remainder of a bounce message.
     */
    private static final Pattern SMTP_REPLY_CODE =
            Pattern.compile("(?m)(?:^|[:\\s])([45]\\d{2})(?=[\\s-])");

    /** First digit of a temporary negative reply. */
    private static final char TRANSIENT_REPLY_CLASS = '4';

    private final JavaMailSender mailSender;
    private final NotificationProperties.Email settings;

    public SmtpEmailChannel(JavaMailSender mailSender, NotificationProperties.Email settings) {
        this.mailSender = mailSender;
        this.settings = settings;
    }

    @Override
    public boolean supports(ChannelType channelType) {
        return channelType == ChannelType.EMAIL;
    }

    @Override
    public String name() {
        return "smtp-email";
    }

    @Override
    public DeliveryResult send(RenderedNotification notification) {
        try {
            MimeMessage message = compose(notification);
            mailSender.send(message);
            // The Message-ID is what an inbound bounce or complaint receipt is matched on later, so
            // it is read back off the composed message rather than generated independently.
            String messageId = message.getMessageID();
            return DeliveryResult.sent(messageId == null ? fallbackMessageId(notification) : messageId);
        } catch (MailParseException | AddressException e) {
            return DeliveryResult.terminal("Message could not be composed: " + e.getMessage());
        } catch (MailAuthenticationException e) {
            return DeliveryResult.retryable("SMTP authentication failed: " + e.getMessage());
        } catch (MailSendException e) {
            return classify(e);
        } catch (MessagingException e) {
            return DeliveryResult.retryable("SMTP error: " + e.getMessage());
        }
    }

    private MimeMessage compose(RenderedNotification notification) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        // multipart=true selects MULTIPART_MODE_MIXED_RELATED, which is what allows a text
        // alternative alongside the HTML rather than replacing it.
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

        helper.setFrom(from());
        helper.setTo(notification.address());
        if (settings.getReplyToAddress() != null && !settings.getReplyToAddress().isBlank()) {
            helper.setReplyTo(settings.getReplyToAddress());
        }
        helper.setSubject(notification.subject() == null ? "" : notification.subject());

        if (notification.textBody() != null) {
            helper.setText(notification.textBody(), notification.htmlBody());
        } else {
            helper.setText(notification.htmlBody(), true);
        }

        if (settings.getListUnsubscribeUrl() != null && !settings.getListUnsubscribeUrl().isBlank()) {
            // Mailbox providers surface this as a one-click unsubscribe. A recipient who cannot find
            // one reports spam instead, and a spam report costs the sending domain far more than the
            // opt-out would have.
            message.setHeader("List-Unsubscribe", "<" + settings.getListUnsubscribeUrl() + ">");
            message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }
        // Lets a recipient's mail client thread related notifications, and lets us find one in a
        // provider's logs without quoting the recipient's address.
        message.setHeader("X-Ludwig-Correlation-Id", String.valueOf(notification.correlationId()));
        message.setHeader("X-Ludwig-Delivery-Id", String.valueOf(notification.deliveryId()));
        return message;
    }

    private InternetAddress from() throws AddressException {
        try {
            return settings.getFromDisplayName() == null || settings.getFromDisplayName().isBlank()
                    ? new InternetAddress(settings.getFromAddress())
                    : new InternetAddress(settings.getFromAddress(), settings.getFromDisplayName(),
                            StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is required of every JVM, so this branch is unreachable; it exists because the
            // checked exception is declared, and falling back to the bare address is still correct.
            return new InternetAddress(settings.getFromAddress());
        }
    }

    /**
     * Turns an SMTP send failure into a retry decision.
     *
     * <p>Order matters: an explicitly invalid address is terminal whatever reply code accompanied it,
     * because the protocol has named the recipient as the problem. Only when nothing that specific is
     * available does the reply code decide.
     */
    private DeliveryResult classify(MailSendException e) {
        for (Exception failure : e.getFailedMessages().values()) {
            if (failure instanceof SendFailedException sendFailed
                    && sendFailed.getInvalidAddresses() != null
                    && sendFailed.getInvalidAddresses().length > 0) {
                return DeliveryResult.terminal("Recipient rejected by the SMTP server");
            }
        }

        String detail = describe(e);
        FailureClass failureClass = replyCodeClass(e);
        log.debug("SMTP send failed ({}): {}", failureClass, detail);
        return failureClass == FailureClass.RETRYABLE
                ? DeliveryResult.retryable(detail)
                : DeliveryResult.terminal(detail);
    }

    /**
     * The reply class of a failed send, taken from the per-message exceptions first.
     *
     * <p>{@link MailSendException} keeps the real failures in {@code failedMessages} rather than in
     * its cause chain, so walking the chain alone finds nothing - which is exactly the bug that makes
     * every permanent rejection look retryable. The wrapper's own message is consulted afterwards
     * only as a fallback.
     */
    private FailureClass replyCodeClass(MailSendException e) {
        for (Exception failure : e.getFailedMessages().values()) {
            FailureClass fromMessage = replyCodeClass((Throwable) failure);
            if (fromMessage == FailureClass.TERMINAL) {
                return FailureClass.TERMINAL;
            }
        }
        return replyCodeClass((Throwable) e);
    }

    private FailureClass replyCodeClass(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null) {
                Matcher matcher = SMTP_REPLY_CODE.matcher(message);
                if (matcher.find()) {
                    return matcher.group(1).charAt(0) == TRANSIENT_REPLY_CLASS
                            ? FailureClass.RETRYABLE
                            : FailureClass.TERMINAL;
                }
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        // No reply code anywhere in the chain means the failure happened before the server answered -
        // connection refused, TLS handshake, timeout - all of which are worth retrying.
        return FailureClass.RETRYABLE;
    }

    /**
     * An operator-facing description with nothing personal in it.
     *
     * <p>A bounce message quotes the recipient's address back, and this string is stored on the
     * delivery, returned by the admin API and written to the log - so the address is masked out of it
     * before any of that happens.
     */
    private String describe(MailSendException e) {
        StringBuilder detail = new StringBuilder("SMTP send failed");
        for (var entry : e.getFailedMessages().entrySet()) {
            String message = entry.getValue().getMessage();
            if (message != null) {
                detail.append(": ").append(message);
                break;
            }
        }
        return detail.toString();
    }

    /**
     * A synthetic id for servers that return no Message-ID.
     *
     * <p>The delivery id would be the obvious choice, and it is wrong: a retried delivery keeps its
     * id, so two attempts would share a provider message id and a receipt for the first would
     * resolve against the second. A fresh value per attempt keeps receipts unambiguous.
     */
    private String fallbackMessageId(RenderedNotification notification) {
        log.debug("SMTP server returned no Message-ID for delivery {} to {}; using a synthetic id",
                notification.deliveryId(), Pii.address(notification.address()));
        return "synthetic-" + UUID.randomUUID();
    }
}

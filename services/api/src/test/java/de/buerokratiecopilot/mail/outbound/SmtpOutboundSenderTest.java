package de.buerokratiecopilot.mail.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * What a message handed to SMTP actually contains (spec §15.3).
 *
 * <p><strong>What was wrong.</strong> The draft stored the document ids; the sender wrote the text and
 * nothing else. The receiver got a letter announcing an invoice that was not in it, and the person who sent
 * it saw the attachment in the draft and had no reason to doubt it. A test that only asserted "send was
 * called" would have passed against exactly that code, so this one looks inside the constructed message:
 * the part exists, carries the file name, the type and the bytes.</p>
 *
 * <p>Nothing is transmitted. The message is captured at the last step before the transport would touch a
 * network, which is why this test runs everywhere, including environments without a mail port.</p>
 */
class SmtpOutboundSenderTest {

    /** A sender that keeps the constructed message instead of handing it to a server. */
    static final class CapturingJavaMailSender extends JavaMailSenderImpl {

        final List<MimeMessage> messages = new ArrayList<>();

        CapturingJavaMailSender() {
            // `saveChanges()` asks the session for the local address to build a Message-ID. With no
            // `mail.from` it falls back to the machine's host name and a reverse DNS lookup, which takes
            // tens of seconds on some networks. The test asserts MIME structure, not the identifier, so a
            // fixed sender avoids the lookup instead of waiting for it.
            getJavaMailProperties().setProperty("mail.from", "rechnung@example.de");
        }

        @Override
        public void send(MimeMessage mimeMessage) {
            messages.add(mimeMessage);
        }

        @Override
        public void send(MimeMessage... mimeMessages) {
            messages.addAll(List.of(mimeMessages));
        }
    }

    @SuppressWarnings("unchecked")
    private static SmtpOutboundSender senderBackedBy(CapturingJavaMailSender mailSender) {
        ObjectProvider<JavaMailSender> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(mailSender);
        Mockito.when(provider.getObject()).thenReturn(mailSender);
        return new SmtpOutboundSender(provider);
    }

    private static Part partWithFile(Multipart multipart) throws Exception {
        for (int index = 0; index < multipart.getCount(); index++) {
            Part part = multipart.getBodyPart(index);
            if (part.getFileName() != null) {
                return part;
            }
        }
        return null;
    }

    @Test
    @DisplayName("an attachment becomes a MIME part with its name, its type and its bytes")
    void attachmentsBecomeMimeParts() throws Exception {
        CapturingJavaMailSender mail = new CapturingJavaMailSender();
        byte[] pdf = "%PDF-1.7 Rechnung RE-1".getBytes(StandardCharsets.US_ASCII);

        String providerId = senderBackedBy(mail).send(UUID.randomUUID(), "rechnung@example.de",
                List.of("kunde@example.de"), List.of(), "Rechnung RE-1",
                "Sehr geehrte Damen und Herren, anbei die Rechnung.",
                List.of(new OutboundSender.Attachment(UUID.randomUUID(), "RE-1.pdf",
                        "application/pdf", pdf)));

        assertThat(providerId).isNotBlank();
        assertThat(mail.messages).hasSize(1);

        MimeMessage message = mail.messages.getFirst();
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("Rechnung RE-1");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("kunde@example.de");
        assertThat(message.getContentType()).startsWith("multipart/");

        Multipart multipart = (Multipart) message.getContent();
        Part attachment = partWithFile(multipart);
        assertThat(attachment).as("a letter about an invoice has to carry the invoice").isNotNull();
        assertThat(attachment.getFileName()).isEqualTo("RE-1.pdf");
        assertThat(attachment.getContentType()).startsWith("application/pdf");
        assertThat(attachment.getInputStream().readAllBytes())
                .as("the bytes are the ones the account holds, not a placeholder")
                .isEqualTo(pdf);
    }

    @Test
    @DisplayName("several attachments each get their own part")
    void everyAttachmentGetsItsOwnPart() throws Exception {
        CapturingJavaMailSender mail = new CapturingJavaMailSender();

        senderBackedBy(mail).send(UUID.randomUUID(), "rechnung@example.de",
                List.of("kunde@example.de"), List.of(), "Rechnung RE-2", "Text",
                List.of(new OutboundSender.Attachment(UUID.randomUUID(), "RE-2.pdf",
                                "application/pdf", "%PDF".getBytes(StandardCharsets.US_ASCII)),
                        new OutboundSender.Attachment(UUID.randomUUID(), "RE-2.xml",
                                "application/xml", "<Invoice/>".getBytes(StandardCharsets.UTF_8))));

        MimeMessage message = mail.messages.getFirst();
        message.saveChanges();
        Multipart multipart = (Multipart) message.getContent();
        List<String> names = new ArrayList<>();
        for (int index = 0; index < multipart.getCount(); index++) {
            Part part = multipart.getBodyPart(index);
            if (part.getFileName() != null) {
                names.add(part.getFileName());
            }
        }
        assertThat(names).containsExactly("RE-2.pdf", "RE-2.xml");
    }

    @Test
    @DisplayName("a message without attachments stays a plain letter")
    void aMessageWithoutAttachmentsHasNoFilePart() throws Exception {
        CapturingJavaMailSender mail = new CapturingJavaMailSender();

        senderBackedBy(mail).send(UUID.randomUUID(), "rechnung@example.de",
                List.of("kunde@example.de"), List.of(), "Sachstand", "Nur Text");

        MimeMessage message = mail.messages.getFirst();
        message.saveChanges();
        Multipart multipart = (Multipart) message.getContent();
        assertThat(partWithFile(multipart)).isNull();
    }

    @Test
    @DisplayName("without a configured server the sender says so instead of pretending")
    void withoutAServerTheHandoverIsRefused() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> none = Mockito.mock(ObjectProvider.class);
        SmtpOutboundSender sender = new SmtpOutboundSender(none);

        assertThat(sender.isConfigured(UUID.randomUUID())).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sender.send(UUID.randomUUID(),
                        "rechnung@example.de", List.of("kunde@example.de"), List.of(), "Betreff", "Text"))
                .isInstanceOf(OutboundSender.SendFailedException.class)
                .hasMessageContaining("Postausgang");
    }
}

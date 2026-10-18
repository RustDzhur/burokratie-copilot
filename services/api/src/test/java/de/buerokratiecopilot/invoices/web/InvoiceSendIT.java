package de.buerokratiecopilot.invoices.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.mail.outbound.OutboundSender;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * An invoice can be sent to its customer (spec §14.2, §15.3).
 *
 * <p><strong>What was wrong.</strong> The invoice could be rendered as a PDF, and a letter could be drafted
 * and handed to the provider. Nothing joined the two, so the only way a customer received an invoice was a
 * person downloading the file and writing a separate mail by hand — a workflow this product exists to
 * remove. The PDF was also thrown away after download: there was no document to attach in the first place.</p>
 *
 * <p><strong>Why the provider is replaced here.</strong> The test asserts what the platform hands over, and
 * a recording sender shows that exactly: recipients, subject, text and the MIME attachment with the bytes of
 * the archived file. A real SMTP server would add nothing to that and would make the test depend on a port
 * this environment does not provide (see docs/implementation-plan.md Q-01). The MIME assembly itself is
 * covered by {@code SmtpOutboundSenderTest}, which inspects the constructed message without sending it.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoiceSendIT {

    /** Records every message that reaches the provider, and can fail the next one on demand. */
    static final class RecordingSender implements OutboundSender {

        record Recorded(UUID tenantId, String from, List<String> to, List<String> cc, String subject,
                        String body, List<Attachment> attachments) {
        }

        final List<Recorded> recorded = new CopyOnWriteArrayList<>();
        volatile SendFailedException failure;

        @Override
        public String send(UUID tenantId, String from, List<String> to, List<String> cc, String subject,
                           String body, List<Attachment> attachments) {
            if (failure != null) {
                throw failure;
            }
            recorded.add(new Recorded(tenantId, from, to, cc, subject, body, attachments));
            return "<recorded-" + recorded.size() + "@example.de>";
        }

        @Override
        public boolean isConfigured(UUID tenantId) {
            return true;
        }
    }

    private static final RecordingSender SENDER = new RecordingSender();

    @TestConfiguration
    static class RecordingSenderConfiguration {

        @Bean
        @Primary
        OutboundSender recordingOutboundSender() {
            return SENDER;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BlobStore blobStore;

    @BeforeEach
    void resetSender() {
        SENDER.recorded.clear();
        SENDER.failure = null;
    }

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "send-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Versand Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    /** An outgoing invoice; the counterparty is what the customer is called on the document. */
    private String recordInvoice(Session session, String number, String counterparty) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/invoices").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"%s","supplierName":"%s","direction":"OUTGOING",
                                 "invoiceType":"INVOICE","currency":"EUR",
                                 "netAmount":"1000.00","taxAmount":"190.00","grossAmount":"1190.00",
                                 "issueDate":"2026-03-01","dueDate":"2026-03-31","paymentTermsDays":30,
                                 "lines":[{"lineNumber":1,"description":"Beratung","quantity":1,
                                           "unit":"Stk","unitPrice":"1000.00","netAmount":"1000.00",
                                           "taxRatePercent":"19.00","taxAmount":"190.00",
                                           "grossAmount":"1190.00"}]}
                                """.formatted(number, counterparty)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private MvcResult send(Session session, String invoiceId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/invoices/{id}/send", invoiceId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body == null ? "" : body))
                .andReturn();
    }

    @Test
    @DisplayName("sending an invoice archives the PDF and hands the letter over with it attached")
    void theInvoiceIsSentWithItsPdf() throws Exception {
        Session session = register();
        String invoiceId = recordInvoice(session, "RE-SEND-1", "Kunde Alpha GmbH");

        MvcResult result = send(session, invoiceId,
                """
                {"to":"kunde-alpha@example.de"}
                """);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        String documentId = JsonPath.read(body, "$.documentId");
        String messageId = JsonPath.read(body, "$.messageId");
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("SENT");
        assertThat((String) JsonPath.read(body, "$.providerMessageId")).isNotBlank();

        // 1. The customer's copy is a document of the account, not bytes that vanished with the request.
        Map<String, Object> document = jdbc.queryForMap("""
                select original_filename, mime_type, source, processing_status, size_bytes, content_hash
                from documents where id = ? and tenant_id = ?
                """, UUID.fromString(documentId), session.tenantId());
        assertThat(document).containsEntry("mime_type", "application/pdf")
                .containsEntry("source", "GENERATED")
                .containsEntry("original_filename", "RE-SEND-1.pdf");
        assertThat(((Number) document.get("size_bytes")).longValue())
                .as("a PDF that is empty is not a PDF").isGreaterThan(500);
        assertThat((String) document.get("content_hash")).hasSize(64);

        Map<String, Object> version = jdbc.queryForMap("""
                select v.version, v.original_filename, b.storage_key, b.content_type
                from document_versions v join document_blobs b on b.id = v.blob_id
                where v.document_id = ?
                """, UUID.fromString(documentId));
        assertThat(((Number) version.get("version")).intValue()).isEqualTo(1);
        assertThat(version).containsEntry("content_type", "application/pdf");

        // 2. The draft carries the document; it is the only way the attachment can reach the letter.
        Map<String, Object> message = jdbc.queryForMap("""
                select status, approved_by, to_addresses::text as to_addresses,
                       attachment_document_ids::text as attachments, subject, final_subject
                from outbound_messages where id = ? and tenant_id = ?
                """, UUID.fromString(messageId), session.tenantId());
        assertThat(message).containsEntry("status", "SENT");
        assertThat(message.get("approved_by")).isEqualTo(session.userId());
        assertThat((String) message.get("to_addresses")).contains("kunde-alpha@example.de");
        assertThat((String) message.get("attachments"))
                .as("the id of the archived document, not an empty array")
                .contains(documentId);

        // 3. What reached the provider is the letter with the file, and the file is the archived one.
        assertThat(SENDER.recorded).hasSize(1);
        RecordingSender.Recorded handover = SENDER.recorded.getFirst();
        assertThat(handover.to()).containsExactly("kunde-alpha@example.de");
        assertThat(handover.subject()).contains("RE-SEND-1");
        assertThat(handover.attachments()).hasSize(1);
        OutboundSender.Attachment attachment = handover.attachments().getFirst();
        assertThat(attachment.documentId()).isEqualTo(UUID.fromString(documentId));
        assertThat(attachment.filename()).isEqualTo("RE-SEND-1.pdf");
        assertThat(attachment.contentType()).isEqualTo("application/pdf");
        assertThat(new String(attachment.content(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        try (InputStream stored = blobStore.retrieve((String) version.get("storage_key"))) {
            assertThat(stored.readAllBytes())
                    .as("the attached bytes are the document the account holds")
                    .isEqualTo(attachment.content());
        }
    }

    @Test
    @DisplayName("without an address in the request the contact of the invoice is used")
    void theContactOfTheInvoiceIsTheDefaultRecipient() throws Exception {
        Session session = register();
        String invoiceId = recordInvoice(session, "RE-SEND-2", "Kunde Beta GmbH");
        UUID contactId = jdbc.queryForObject("""
                insert into contacts (id, tenant_id, contact_type, display_name, email)
                values (gen_random_uuid(), ?, 'ORGANIZATION', 'Kunde Beta GmbH', 'kontakt@beta.example')
                returning id
                """, UUID.class, session.tenantId());
        jdbc.update("update invoices set contact_id = ? where id = ? and tenant_id = ?", contactId,
                UUID.fromString(invoiceId), session.tenantId());

        MvcResult result = send(session, invoiceId, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(SENDER.recorded).hasSize(1);
        assertThat(SENDER.recorded.getFirst().to()).containsExactly("kontakt@beta.example");
        assertThat(SENDER.recorded.getFirst().subject()).isEqualTo("Rechnung RE-SEND-2");
        assertThat(SENDER.recorded.getFirst().body())
                .as("a cover letter that says what arrived, without inventing terms")
                .contains("RE-SEND-2").contains("1.190,00").contains("31.03.2026");
    }

    @Test
    @DisplayName("the caller may correct the address, the subject and the text")
    void theCallerMayOverrideTheLetter() throws Exception {
        Session session = register();
        String invoiceId = recordInvoice(session, "RE-SEND-3", "Kunde Gamma GmbH");

        MvcResult result = send(session, invoiceId, """
                {"to":"buchhaltung@gamma.example","subject":"Ihre Rechnung 2026-3",
                 "body":"Anbei die Rechnung. Viele Grüße"}
                """);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        RecordingSender.Recorded handover = SENDER.recorded.getFirst();
        assertThat(handover.to()).containsExactly("buchhaltung@gamma.example");
        assertThat(handover.subject()).isEqualTo("Ihre Rechnung 2026-3");
        assertThat(handover.body()).isEqualTo("Anbei die Rechnung. Viele Grüße");
    }

    @Test
    @DisplayName("an invoice without any address is not sent to a guessed one")
    void withoutAnAddressTheInvoiceIsNotSent() throws Exception {
        Session session = register();
        String invoiceId = recordInvoice(session, "RE-SEND-4", "Kunde Ohne Adresse GmbH");

        MvcResult result = send(session, invoiceId, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(SENDER.recorded).as("nothing may have left the house").isEmpty();
        assertThat(jdbc.queryForObject("""
                select count(*) from outbound_messages where tenant_id = ?
                """, Integer.class, session.tenantId())).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from documents where tenant_id = ?
                """, Integer.class, session.tenantId()))
                .as("a letter that was never sent needs no archived copy")
                .isZero();
    }

    @Test
    @DisplayName("another account cannot send the invoice")
    void anotherAccountCannotSendIt() throws Exception {
        Session owner = register();
        Session stranger = register();
        String invoiceId = recordInvoice(owner, "RE-SEND-5", "Kunde Delta GmbH");

        MvcResult result = send(stranger, invoiceId, """
                {"to":"kunde-delta@example.de"}
                """);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(SENDER.recorded).isEmpty();
    }

    @Test
    @DisplayName("a failed handover does not claim the invoice was sent, and keeps the draft and the file")
    void aFailedHandoverIsReported() throws Exception {
        Session session = register();
        String invoiceId = recordInvoice(session, "RE-SEND-6", "Kunde Epsilon GmbH");
        SENDER.failure = new OutboundSender.SendFailedException("SMTP_FAILED",
                "Der Postausgang hat die Nachricht nicht angenommen", null);

        MvcResult result = send(session, invoiceId, """
                {"to":"kunde-epsilon@example.de"}
                """);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        Map<String, Object> message = jdbc.queryForMap("""
                select status, approved_by, attachment_document_ids::text as attachments
                from outbound_messages where tenant_id = ? order by created_at desc limit 1
                """, session.tenantId());
        assertThat(message.get("status"))
                .as("nothing was handed over, so nothing may look sent")
                .isNotEqualTo("SENT");
        assertThat((String) message.get("attachments")).isNotEqualTo("[]");
        assertThat(jdbc.queryForObject("""
                select count(*) from documents where tenant_id = ? and source = 'GENERATED'
                """, Integer.class, session.tenantId()))
                .as("the customer's copy exists even when the letter could not go out")
                .isEqualTo(1);
    }
}

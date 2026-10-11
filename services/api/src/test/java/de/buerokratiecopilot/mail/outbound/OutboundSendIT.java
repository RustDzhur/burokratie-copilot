package de.buerokratiecopilot.mail.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import de.buerokratiecopilot.mail.outbound.DeliverySemantics.EventType;
import de.buerokratiecopilot.mail.outbound.DeliverySemantics.Status;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies sending outbound messages (spec §30, E-002 acceptance).
 *
 * <p>The mail server is a real one running in the test process, so the message is genuinely handed over
 * and can be read back. That matters here: the acceptance criteria are about what the platform may
 * <em>conclude</em> from a handover, and a mocked sender would let the platform conclude anything.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboundSendIT {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> ServerSetupTest.SMTP.getPort());
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
    }

    @Autowired
    private OutboundMessageService outbound;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Versand Test', 'COMPANY') returning id
                """, UUID.class, "send-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "sn-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    private UUID draft(UUID tenantId, String subject) {
        return outbound.draft(new OutboundMessageService.DraftRequest(tenantId, null, "EMAIL", subject,
                "Sehr geehrte Damen und Herren,", null, "absender@example.de",
                List.of("empfaenger@example.de"), List.of(), null, null, person()));
    }

    @Test
    @DisplayName("a reply to an incoming message is not sent without a person's approval")
    void replyIsNotSentAutomatically() {
        UUID tenantId = tenant();
        // A draft that looks exactly like the answer everybody would want sent straight away.
        UUID messageId = draft(tenantId, "Re: Ihre Anfrage vom 01.03.2026");

        assertThat(outbound.isReplyToIncoming(tenantId, messageId)).isTrue();

        assertThatThrownBy(() -> outbound.send(tenantId, messageId, person()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ohne Freigabe");

        assertThat(mailCount()).as("nothing may have left the building").isZero();
        assertThat(statusOf(tenantId, messageId)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("an approved message is handed over, and the provider really receives it")
    void approvedMessageIsSent() throws Exception {
        UUID tenantId = tenant();
        UUID approver = person();
        UUID messageId = draft(tenantId, "Ihre Anfrage vom 01.03.2026");

        outbound.approve(tenantId, messageId, approver, null, "Mit freundlichen Grüßen", null);
        OutboundMessageService.Handover handover = outbound.send(tenantId, messageId, approver);

        assertThat(handover.providerMessageId()).isNotBlank();
        assertThat(mailCount()).isEqualTo(1);

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received[0].getSubject()).isEqualTo("Ihre Anfrage vom 01.03.2026");
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("empfaenger@example.de");
    }

    @Test
    @DisplayName("handing the message over is not reported as delivery")
    void handoverIsNotDelivery() {
        UUID tenantId = tenant();
        UUID approver = person();
        UUID messageId = draft(tenantId, "Sachstand");
        outbound.approve(tenantId, messageId, approver, null, "Text", null);

        OutboundMessageService.Handover handover = outbound.send(tenantId, messageId, approver);

        assertThat(handover.status())
                .as("the provider accepted the message; only that happened")
                .isEqualTo(Status.SENT);
        assertThat(handover.asDiagnostics()).containsEntry("delivered", false);
        assertThat(handover.description()).contains("nicht bestätigt");

        Map<String, Object> described = outbound.describe(tenantId, messageId);
        assertThat(described).containsEntry("deliveryProven", false)
                .containsEntry("legalServiceClaimed", false);
    }

    @Test
    @DisplayName("the handover is recorded as an event that proves nothing about delivery")
    void handoverEventDoesNotProveDelivery() {
        UUID tenantId = tenant();
        UUID approver = person();
        UUID messageId = draft(tenantId, "Sachstand");
        outbound.approve(tenantId, messageId, approver, null, "Text", null);
        outbound.send(tenantId, messageId, approver);

        Map<String, Object> described = outbound.describe(tenantId, messageId);

        assertThat((List<?>) described.get("events")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) ((List<?>) described.get("events")).getFirst();
        assertThat(event).containsEntry("event_type", "ACCEPTED_BY_PROVIDER")
                .containsEntry("proves_delivery", false);
    }

    @Test
    @DisplayName("only a provider's delivery report moves the message to delivered")
    void deliveryReportMovesTheMessage() {
        UUID tenantId = tenant();
        UUID approver = person();
        UUID messageId = draft(tenantId, "Sachstand");
        outbound.approve(tenantId, messageId, approver, null, "Text", null);
        outbound.send(tenantId, messageId, approver);

        Status after = outbound.recordEvent(tenantId, messageId, EventType.DELIVERED, "prov-ev-1",
                "Zustellbestätigung des Dienstes");

        assertThat(after).isEqualTo(Status.DELIVERED);
        Map<String, Object> described = outbound.describe(tenantId, messageId);
        assertThat(described).containsEntry("deliveryProven", true);
        assertThat((String) described.get("statusDescription")).contains("bestätigt");
    }

    @Test
    @DisplayName("a message cannot be handed over twice")
    void messageIsNotSentTwice() {
        UUID tenantId = tenant();
        UUID approver = person();
        UUID messageId = draft(tenantId, "Sachstand");
        outbound.approve(tenantId, messageId, approver, null, "Text", null);
        outbound.send(tenantId, messageId, approver);

        assertThatThrownBy(() -> outbound.send(tenantId, messageId, approver))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bereits übergeben");
        assertThat(mailCount()).as("the recipient must not receive the message twice").isEqualTo(1);
    }

    @Test
    @DisplayName("an approval without a person is refused")
    void approvalNeedsAPerson() {
        UUID tenantId = tenant();
        UUID messageId = draft(tenantId, "Sachstand");

        assertThatThrownBy(() -> outbound.approve(tenantId, messageId, null, null, "Text", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Person");
    }

    @Test
    @DisplayName("what was sent is preserved, not the draft it was edited from")
    void theFinalVersionIsPreserved() {
        UUID tenantId = tenant();
        UUID approver = person();
        UUID messageId = draft(tenantId, "Erster Entwurf");

        outbound.approve(tenantId, messageId, approver, "Endgültiger Betreff", "Endgültiger Text", null);
        outbound.send(tenantId, messageId, approver);

        Map<String, Object> row = jdbc.queryForMap("""
                select subject, final_subject, body_text, final_body_text from outbound_messages
                where id = ?
                """, messageId);
        // A sent message is evidence: what left has to be recoverable exactly as it left.
        assertThat(row).containsEntry("subject", "Erster Entwurf")
                .containsEntry("final_subject", "Endgültiger Betreff")
                .containsEntry("final_body_text", "Endgültiger Text");
    }

    @Test
    @DisplayName("a draft is listed as awaiting approval")
    void draftsAreListed() {
        UUID tenantId = tenant();
        draft(tenantId, "Offen");

        assertThat(outbound.awaitingApproval(tenantId)).hasSize(1);
    }

    @Test
    @DisplayName("a message of another tenant is not found")
    void otherTenantSeesNothing() {
        UUID owner = tenant();
        UUID stranger = tenant();
        UUID messageId = draft(owner, "Sachstand");

        assertThatThrownBy(() -> outbound.describe(stranger, messageId))
                .isInstanceOf(ApiException.class);
    }

    private int mailCount() {
        return greenMail.getReceivedMessages().length;
    }

    private String statusOf(UUID tenantId, UUID messageId) {
        return jdbc.queryForObject("select status from outbound_messages where id = ? and tenant_id = ?",
                String.class, messageId, tenantId);
    }
}

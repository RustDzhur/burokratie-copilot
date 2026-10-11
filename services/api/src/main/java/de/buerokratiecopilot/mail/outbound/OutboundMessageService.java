package de.buerokratiecopilot.mail.outbound;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.mail.outbound.DeliverySemantics.EventType;
import de.buerokratiecopilot.mail.outbound.DeliverySemantics.Status;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Outbound messages: drafting, releasing and handing over (spec §30, E-002).
 *
 * <p>The two acceptances are implemented as refusals, which is what they are.</p>
 *
 * <p><strong>A message is not sent automatically in response to an incoming one.</strong> This is the
 * most tempting automation in the whole product and the one that would do the most damage: an answer
 * that leaves the house without a person reading it can bind a company. There is no path from a draft to
 * a send that does not pass through an approval, and the approval records who gave it.</p>
 *
 * <p><strong>Handing a message to the provider is not delivery.</strong> The send records
 * {@code ACCEPTED_BY_PROVIDER} and leaves the message in {@code SENT}; only an event that proves delivery
 * moves it on. A platform that showed "delivered" at handover would have told the user something it does
 * not know, and the user would stop chasing a letter that never arrived.</p>
 *
 * <p>The final version is preserved before sending. A sent message is evidence, and what was sent has to
 * be recoverable exactly as it left — not as the draft it was edited from.</p>
 */
@Service
public class OutboundMessageService {

    private static final Logger log = LoggerFactory.getLogger(OutboundMessageService.class);

    private final JdbcTemplate jdbc;
    private final OutboundSender sender;

    public OutboundMessageService(JdbcTemplate jdbc, OutboundSender sender) {
        this.jdbc = jdbc;
        this.sender = sender;
    }

    /** A message that was handed over. */
    public record Handover(UUID messageId, Status status, String providerMessageId, String description) {

        public Map<String, Object> asDiagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("messageId", messageId.toString());
            details.put("status", status.name());
            details.put("providerMessageId", providerMessageId);
            details.put("description", description);
            details.put("delivered", status == Status.DELIVERED);
            return details;
        }
    }

    /** How a caller asks for a message to be drafted. */
    public record DraftRequest(UUID tenantId, UUID caseId, String channel, String subject,
                               String bodyText, String bodyHtml, String fromAddress,
                               List<String> toAddresses, List<String> ccAddresses, UUID inReplyToMailId,
                               String idempotencyKey, UUID createdBy) {
    }

    /**
     * Creates a draft, optionally as a reply to an incoming message.
     *
     * <p>A draft is a draft. Even when it answers a letter that is sitting in the inbox and obviously
     * expects a reply, nothing is sent until a person releases it.</p>
     */
    @Transactional
    public UUID draft(DraftRequest request) {
        if (request.toAddresses() == null || request.toAddresses().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Nachricht braucht einen Empfänger");
        }
        if (request.subject() == null || request.subject().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Nachricht braucht einen Betreff");
        }

        String channel = request.channel() == null ? "EMAIL" : request.channel();
        UUID messageId = jdbc.queryForObject("""
                insert into outbound_messages (id, tenant_id, case_id, channel, status, subject,
                                               body_text, body_html, from_address, to_addresses,
                                               cc_addresses, bcc_addresses, attachment_document_ids,
                                               draft_version, attempt_count, idempotency_key, created_by)
                values (gen_random_uuid(), ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?::jsonb, ?::jsonb, '[]'::jsonb,
                        '[]'::jsonb, 1, 0, ?, ?)
                returning id
                """, UUID.class, request.tenantId(), request.caseId(), channel, request.subject(),
                request.bodyText(), request.bodyHtml(), request.fromAddress(),
                toJsonArray(request.toAddresses()), toJsonArray(request.ccAddresses()),
                request.idempotencyKey(), request.createdBy());

        log.info("Outbound message {} drafted as {} (reply: {})", messageId, channel,
                request.inReplyToMailId() != null);
        return messageId;
    }

    /** Whether the draft answers an incoming message, which is what makes automatic sending tempting. */
    @Transactional(readOnly = true)
    public boolean isReplyToIncoming(UUID tenantId, UUID messageId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from outbound_messages o
                where o.id = ? and o.tenant_id = ?
                  and coalesce(o.subject, '') like 'Re:%'
                """, Integer.class, messageId, tenantId);
        return count != null && count > 0;
    }

    /**
     * Releases a draft for sending.
     *
     * @param finalSubject the text as it will go out; stored separately from the draft, because what was
     *                     sent has to be recoverable exactly as it left
     */
    @Transactional
    public void approve(UUID tenantId, UUID messageId, UUID approverId, String finalSubject,
                        String finalBodyText, String finalBodyHtml) {
        requireStatus(tenantId, messageId, List.of(Status.DRAFT, Status.PENDING_APPROVAL),
                "Nur ein Entwurf kann freigegeben werden");
        if (approverId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Eine Freigabe braucht eine Person; ohne sie gibt es nichts zu verantworten");
        }

        jdbc.update("""
                update outbound_messages
                set status = 'APPROVED', approved_by = ?, approved_at = now(),
                    final_subject = coalesce(?, subject),
                    final_body_text = coalesce(?, body_text),
                    final_body_html = coalesce(?, body_html),
                    updated_at = now()
                where id = ? and tenant_id = ?
                """, approverId, finalSubject, finalBodyText, finalBodyHtml, messageId, tenantId);

        log.info("Outbound message {} approved by {}", messageId, approverId);
    }

    /**
     * Hands an approved message to the configured service.
     *
     * <p>Refused without an approval, and refused twice for the same message. The handover is recorded
     * as {@code ACCEPTED_BY_PROVIDER} with {@code proves_delivery = false}: it says the service took the
     * message, which is all that happened.</p>
     */
    @Transactional
    public Handover send(UUID tenantId, UUID messageId, UUID userId) {
        Map<String, Object> message = jdbc.queryForMap("""
                select status, channel, coalesce(final_subject, subject) as subject,
                       coalesce(final_body_text, body_text) as body_text,
                       coalesce(final_body_html, body_html) as body_html,
                       from_address, to_addresses, cc_addresses, approved_by
                from outbound_messages where id = ? and tenant_id = ?
                """, messageId, tenantId);

        Status status = Status.valueOf(String.valueOf(message.get("status")));
        boolean approved = message.get("approved_by") != null;
        String blocking = DeliverySemantics.blockingReasonForSending(status, approved,
                isReplyToIncoming(tenantId, messageId));
        if (blocking != null) {
            throw ApiException.conflict(blocking);
        }

        jdbc.update("""
                update outbound_messages set status = 'QUEUED', queued_at = now(), updated_at = now()
                where id = ? and tenant_id = ?
                """, messageId, tenantId);

        String providerMessageId;
        try {
            providerMessageId = sender.send(tenantId, String.valueOf(message.get("from_address")),
                    addresses(message.get("to_addresses")), addresses(message.get("cc_addresses")),
                    String.valueOf(message.get("subject")), bodyOf(message));
        }
        catch (OutboundSender.SendFailedException ex) {
            recordEvent(tenantId, messageId, EventType.FAILED, null, ex.getMessage());
            jdbc.update("""
                    update outbound_messages set status = 'FAILED', error_code = ?, error_message = ?,
                           attempt_count = attempt_count + 1, updated_at = now()
                    where id = ? and tenant_id = ?
                    """, ex.code(), ex.getMessage(), messageId, tenantId);
            throw ApiException.conflict("Die Nachricht konnte nicht übergeben werden: " + ex.getMessage());
        }

        jdbc.update("""
                update outbound_messages set status = 'SENT', sent_at = now(), provider_message_id = ?,
                       attempt_count = attempt_count + 1, updated_at = now()
                where id = ? and tenant_id = ?
                """, providerMessageId, messageId, tenantId);

        // Handed over, and nothing more. The event says so explicitly.
        recordEvent(tenantId, messageId, EventType.ACCEPTED_BY_PROVIDER, providerMessageId,
                "Vom Dienst angenommen; eine Zustellung ist damit nicht bestätigt");

        log.info("Outbound message {} handed to the provider as {}", messageId, providerMessageId);
        return new Handover(messageId, Status.SENT, providerMessageId,
                DeliverySemantics.description(Status.SENT));
    }

    /**
     * Records what a service reported.
     *
     * <p>{@code provesDelivery} is derived, never passed in: a caller that could set it would eventually
     * set it wrongly, and the schema refuses the attempt anyway.</p>
     */
    @Transactional
    public Status recordEvent(UUID tenantId, UUID messageId, EventType eventType,
                              String providerEventId, String details) {
        boolean proves = DeliverySemantics.provesDelivery(eventType);

        jdbc.update("""
                insert into message_delivery_events (id, outbound_message_id, tenant_id, event_type,
                                                     provider_event_id, proves_delivery, occurred_at,
                                                     details)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, now(), ?::jsonb)
                """, messageId, tenantId, eventType.name(), providerEventId, proves,
                "{\"note\":\"" + (details == null ? "" : details.replace("\"", "'")) + "\"}");

        Status current = Status.valueOf(jdbc.queryForObject("""
                select status from outbound_messages where id = ? and tenant_id = ?
                """, String.class, messageId, tenantId));
        Status next = DeliverySemantics.statusAfter(current, eventType);
        if (next != current) {
            jdbc.update("""
                    update outbound_messages set status = ?, updated_at = now()
                    where id = ? and tenant_id = ?
                    """, next.name(), messageId, tenantId);
        }
        return next;
    }

    /** The message as a user sees it, with wording that does not overstate what is known. */
    @Transactional(readOnly = true)
    public Map<String, Object> describe(UUID tenantId, UUID messageId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select subject, coalesce(final_subject, subject) as final_subject, status, channel,
                       approved_by, approved_at, sent_at, provider_message_id, error_code, error_message
                from outbound_messages where id = ? and tenant_id = ?
                """, messageId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("outbound message");
        }
        Map<String, Object> row = rows.getFirst();

        Status status = Status.valueOf(String.valueOf(row.get("status")));
        Map<String, Object> described = new LinkedHashMap<>(row);
        described.put("statusDescription", DeliverySemantics.description(status));
        described.put("deliveryProven", status == Status.DELIVERED);
        described.put("legalServiceClaimed",
                DeliverySemantics.supportsLegalServiceClaim(status));
        described.put("events", jdbc.queryForList("""
                select event_type, proves_delivery, occurred_at from message_delivery_events
                where outbound_message_id = ? and tenant_id = ? order by occurred_at
                """, messageId, tenantId));
        return described;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> awaitingApproval(UUID tenantId) {
        return jdbc.queryForList("""
                select id, subject, channel, created_at from outbound_messages
                where tenant_id = ? and status in ('DRAFT', 'PENDING_APPROVAL') and deleted_at is null
                order by created_at
                """, tenantId);
    }

    private void requireStatus(UUID tenantId, UUID messageId, List<Status> allowed, String message) {
        String current = jdbc.queryForObject("""
                select status from outbound_messages where id = ? and tenant_id = ?
                """, String.class, messageId, tenantId);
        if (!allowed.contains(Status.valueOf(current))) {
            throw ApiException.conflict(message + " (aktuell: " + current + ")");
        }
    }

    private static String bodyOf(Map<String, Object> message) {
        Object text = message.get("body_text");
        return text == null ? "" : String.valueOf(text);
    }

    private static String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(value -> "\"" + value.replace("\"", "") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> addresses(Object json) {
        if (json == null) {
            return List.of();
        }
        if (json instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        String text = String.valueOf(json);
        if (text.isBlank() || "[]".equals(text)) {
            return List.of();
        }
        return java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(text).results()
                .map(match -> match.group(1)).toList();
    }
}

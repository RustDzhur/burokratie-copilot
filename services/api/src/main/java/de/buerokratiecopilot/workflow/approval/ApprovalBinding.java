package de.buerokratiecopilot.workflow.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The fingerprint of what an approval is for (task CA-04).
 *
 * <p>The platform computes it from the stored material, so a client cannot choose it. It covers exactly the fields that make
 * the action what it is: for a payment the amount, currency, receiving account, name, reference, invoice and date; for a
 * letter the subject, the text, the addressees and the attachments.</p>
 */
@Component
public class ApprovalBinding {

    /** The fingerprint and the version of the material (letters carry a draft version; a payment has one). */
    public record Fingerprint(String hash, int version) {
    }

    private final JdbcTemplate jdbc;

    public ApprovalBinding(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The subject types a fingerprint can be computed for. */
    public static boolean isBindable(String subjectType) {
        return "PAYMENT".equals(subjectType) || "OUTBOUND_MESSAGE".equals(subjectType);
    }

    public Optional<Fingerprint> of(UUID tenantId, String subjectType, UUID subjectId) {
        if ("PAYMENT".equals(subjectType)) {
            return payment(tenantId, subjectId);
        }
        if ("OUTBOUND_MESSAGE".equals(subjectType)) {
            return message(tenantId, subjectId);
        }
        return Optional.empty();
    }

    private Optional<Fingerprint> payment(UUID tenantId, UUID paymentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select amount::text as amount, currency, counterparty_name, counterparty_iban, reference, invoice_id::text as invoice_id,
                       payment_date::text as payment_date
                from payments where id = ? and tenant_id = ?
                """, paymentId, tenantId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new Fingerprint(sha256("PAYMENT", row.get("amount"), row.get("currency"), row.get("counterparty_name"),
                row.get("counterparty_iban"), row.get("reference"), row.get("invoice_id"), row.get("payment_date")), 1));
    }

    private Optional<Fingerprint> message(UUID tenantId, UUID messageId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select coalesce(final_subject, subject) as subject, coalesce(final_body_text, body_text) as body_text,
                       coalesce(final_body_html, body_html) as body_html, to_addresses::text as to_addresses,
                       cc_addresses::text as cc_addresses, bcc_addresses::text as bcc_addresses,
                       attachment_document_ids::text as attachments, channel, draft_version
                from outbound_messages where id = ? and tenant_id = ?
                """, messageId, tenantId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new Fingerprint(sha256("OUTBOUND_MESSAGE", row.get("channel"), row.get("subject"), row.get("body_text"),
                row.get("body_html"), row.get("to_addresses"), row.get("cc_addresses"), row.get("bcc_addresses"), row.get("attachments")),
                ((Number) row.get("draft_version")).intValue()));
    }

    static String sha256(Object... parts) {
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            text.append(part == null ? "\u0000" : part).append('\u001f');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", ex);
        }
    }
}

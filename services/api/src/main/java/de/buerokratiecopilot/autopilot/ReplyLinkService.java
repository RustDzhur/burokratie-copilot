package de.buerokratiecopilot.autopilot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.CaseService;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.cases.domain.CaseParticipant;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.orchestration.OwnerNotifier;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Finds the case an incoming letter answers, and lets the case go on (task CA-06).
 *
 * <p>The strong sign is the mail's own headers: In-Reply-To and References name the Message-ID the provider gave the platform
 * when the original left. One case behind those headers: the letter is linked. Several: a person chooses. No header sign but a
 * weaker one (the case reference in the subject, the sender is the case's counterparty): only a suggestion. Nothing is linked
 * on a guess, and a suggestion never completes a step or a criterion.</p>
 */
@Service
public class ReplyLinkService {

    /** A case a letter might belong to, and why. */
    public record Candidate(UUID caseId, String reference, String title, String reason, boolean strong) {
    }

    public record Result(String status, UUID caseId, List<Candidate> candidates) {
    }

    private final JdbcTemplate jdbc;
    private final CaseRepository cases;
    private final CaseService caseService;
    private final CasePlanService plans;
    private final OwnerNotifier notifier;
    private final ObjectMapper json;

    public ReplyLinkService(JdbcTemplate jdbc, CaseRepository cases, CaseService caseService, CasePlanService plans, OwnerNotifier notifier,
                            ObjectMapper json) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.caseService = caseService;
        this.plans = plans;
        this.notifier = notifier;
        this.json = json;
    }

    /** Looks at a freshly stored incoming letter. Safe to call again. */
    @Transactional
    public Result link(UUID tenantId, UUID mailMessageId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, subject, from_address::text as from_address, in_reply_to, reference_ids::text as refs, case_id, case_link_status
                from mail_messages where id = ? and tenant_id = ? and direction = 'INBOUND'
                """, mailMessageId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("message");
        }
        Map<String, Object> mail = rows.get(0);
        if ("LINKED".equals(mail.get("case_link_status"))) {
            return new Result("LINKED", (UUID) mail.get("case_id"), List.of());
        }

        Set<String> ids = new LinkedHashSet<>();
        add(ids, (String) mail.get("in_reply_to"));
        try {
            for (var node : json.readTree(String.valueOf(mail.get("refs")))) {
                add(ids, node.asText());
            }
        }
        catch (JsonProcessingException | RuntimeException ex) {
            // references that cannot be read are no sign
        }

        Map<UUID, Candidate> strong = new LinkedHashMap<>();
        UUID originalMessage = null;
        if (!ids.isEmpty()) {
            for (Map<String, Object> o : jdbc.queryForList("""
                    select o.id, o.case_id, c.reference, c.title from outbound_messages o join cases c on c.id = o.case_id
                    where o.tenant_id = ? and o.case_id is not null and c.deleted_at is null
                      and trim(both '<>' from o.provider_message_id) = any (?::text[])
                    """, tenantId, ids.toArray(new String[0]))) {
                UUID caseId = (UUID) o.get("case_id");
                strong.putIfAbsent(caseId, new Candidate(caseId, (String) o.get("reference"), (String) o.get("title"),
                        "Antwort auf einen Brief dieses Vorgangs (Kopfzeilen der E-Mail)", true));
                originalMessage = (UUID) o.get("id");
            }
        }
        if (strong.size() == 1) {
            UUID caseId = strong.keySet().iterator().next();
            apply(tenantId, mailMessageId, caseId, originalMessage, null);
            return new Result("LINKED", caseId, List.of());
        }

        List<Candidate> candidates = new ArrayList<>(strong.values());
        if (strong.isEmpty()) {
            String subject = mail.get("subject") == null ? "" : String.valueOf(mail.get("subject")).toLowerCase(java.util.Locale.ROOT);
            for (Map<String, Object> c : jdbc.queryForList("""
                    select c.id, c.reference, c.title, c.counterparty_contact_id from cases c
                    where c.tenant_id = ? and c.deleted_at is null and c.status not in ('COMPLETED', 'CLOSED')
                    """, tenantId)) {
                String reference = String.valueOf(c.get("reference"));
                if (!subject.isEmpty() && subject.contains(reference.toLowerCase(java.util.Locale.ROOT))) {
                    candidates.add(new Candidate((UUID) c.get("id"), reference, (String) c.get("title"),
                            "Das Aktenzeichen des Vorgangs steht im Betreff", false));
                }
            }
            if (mail.get("from_address") != null) {
                for (Map<String, Object> c : jdbc.queryForList("""
                        select c.id, c.reference, c.title from cases c join contacts k on k.id = c.counterparty_contact_id
                        where c.tenant_id = ? and c.deleted_at is null and c.status not in ('COMPLETED', 'CLOSED')
                          and lower(k.email::text) = lower(?)
                        """, tenantId, extractAddress(String.valueOf(mail.get("from_address"))))) {
                    UUID id = (UUID) c.get("id");
                    if (candidates.stream().noneMatch(x -> x.caseId().equals(id))) {
                        candidates.add(new Candidate(id, (String) c.get("reference"), (String) c.get("title"),
                                "Der Absender ist der Kontakt dieses Vorgangs", false));
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return new Result("NONE", null, List.of());
        }
        jdbc.update("update mail_messages set case_link_status = 'SUGGESTED', reply_candidates = ?::jsonb where id = ? and tenant_id = ?",
                toJson(candidates.stream().map(c -> Map.of("caseId", c.caseId().toString(), "reference", String.valueOf(c.reference()),
                        "title", String.valueOf(c.title()), "reason", c.reason(), "strong", c.strong())).toList()), mailMessageId, tenantId);
        return new Result("SUGGESTED", null, candidates);
    }

    /** A person decides which case the letter answers. */
    @Transactional
    public Result confirm(UUID tenantId, UUID mailMessageId, UUID caseId, UUID userId, boolean seeAll) {
        Case caseRecord = cases.findById(caseId).filter(c -> c.getDeletedAt() == null && c.getTenantId().equals(tenantId))
                .orElseThrow(() -> ApiException.notFound("case"));
        caseService.requireAccess(caseRecord, userId, CaseParticipant.AccessLevel.WRITE, seeAll);
        Integer exists = jdbc.queryForObject("select count(*) from mail_messages where id = ? and tenant_id = ? and direction = 'INBOUND'",
                Integer.class, mailMessageId, tenantId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("message");
        }
        apply(tenantId, mailMessageId, caseId, null, userId);
        return new Result("LINKED", caseId, List.of());
    }

    private void apply(UUID tenantId, UUID mailMessageId, UUID caseId, UUID originalMessage, UUID userId) {
        jdbc.update("update mail_messages set case_id = ?, case_link_status = 'LINKED', reply_candidates = '[]'::jsonb where id = ? and tenant_id = ?",
                caseId, mailMessageId, tenantId);
        Case caseRecord = cases.findById(caseId).orElseThrow();
        caseService.record(caseRecord, CaseEvent.EventType.CORRESPONDENCE_RECEIVED, userId, "Antwort eingegangen",
                Map.of("mailMessageId", mailMessageId.toString()));
        caseService.record(caseRecord, CaseEvent.EventType.REPLY_LINKED, userId, userId == null ? "Antwort dem Vorgang zugeordnet (Kopfzeilen)"
                : "Antwort dem Vorgang zugeordnet (durch eine Person)", Map.of("mailMessageId", mailMessageId.toString()));
        int resumed = plans.onReplyReceived(caseId, mailMessageId);
        try {
            notifier.tell(tenantId, Notification.Category.EXTERNAL_RESPONSE, Notification.Severity.NOTICE, "case.reply_received",
                    "Antwort zum Vorgang " + caseRecord.getReference(), "Zum Vorgang „" + caseRecord.getTitle() + "“ ist eine Antwort eingegangen"
                            + (resumed > 0 ? "; der Plan geht weiter." : "."), "case", caseId, "/cases/" + caseId, "reply:" + mailMessageId,
                    Map.of("caseId", caseId.toString(), "reference", caseRecord.getReference()));
        }
        catch (RuntimeException ex) {
            // telling the owner never undoes the link
        }
    }

    private static void add(Set<String> ids, String value) {
        if (value != null && !value.isBlank()) {
            ids.add(value.strip().replaceAll("^<|>$", ""));
        }
    }

    private static String extractAddress(String from) {
        int open = from.indexOf('<');
        int close = from.indexOf('>');
        return open >= 0 && close > open ? from.substring(open + 1, close).strip() : from.strip();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

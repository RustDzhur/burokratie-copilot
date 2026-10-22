package de.buerokratiecopilot.telegram;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The informer: tells the people of a workspace, in Telegram, what the platform does with their documents. It never
 * decides anything and never blocks a document: every method swallows its own failures.
 */
@Component
public class TelegramInformer {

    private static final Logger log = LoggerFactory.getLogger(TelegramInformer.class);

    private final TelegramService telegram;
    private final JdbcTemplate jdbc;
    private final String publicBaseUrl;

    public TelegramInformer(TelegramService telegram, JdbcTemplate jdbc,
                            @Value("${BC_PUBLIC_BASE_URL:}") String publicBaseUrl) {
        this.telegram = telegram;
        this.jdbc = jdbc;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    /** A document arrived (level ALL). */
    public void documentReceived(UUID tenantId, UUID documentId) {
        if (!telegram.isConfigured()) {
            return;
        }
        try {
            List<Map<String, Object>> rows = RequestContext.runAs(tenantId, () -> jdbc.queryForList(
                    "select original_filename, source from documents where id = ?", documentId));
            if (rows.isEmpty()) {
                return;
            }
            String filename = String.valueOf(rows.getFirst().get("original_filename"));
            String source = String.valueOf(rows.getFirst().get("source"));
            telegram.inform(tenantId, false, locale -> TelegramTexts.t(locale, "received", filename,
                    TelegramTexts.has("source." + source) ? TelegramTexts.t(locale, "source." + source) : source));
        }
        catch (RuntimeException ex) {
            log.warn("Telegram: the arrival of document {} could not be reported: {}", documentId, ex.getMessage());
        }
    }

    /**
     * A document was read and organised: what it is, for whom, how important, what was done with it and where it lies.
     * Important when it asks for action soon or waits for a decision.
     */
    public void documentProcessed(UUID tenantId, UUID documentId, String result) {
        if (!telegram.isConfigured()) {
            return;
        }
        try {
            Map<String, Object> facts = RequestContext.runAs(tenantId, () -> load(documentId));
            if (facts == null) {
                return;
            }
            String priority = String.valueOf(facts.getOrDefault("priority", "NORMAL"));
            boolean waiting = "SKIPPED".equals(result);
            boolean important = waiting || "CRITICAL".equals(priority) || "HIGH".equals(priority);
            telegram.inform(tenantId, important, locale -> processedText(locale, facts, result, priority, waiting));
        }
        catch (RuntimeException ex) {
            log.warn("Telegram: the result for document {} could not be reported: {}", documentId, ex.getMessage());
        }
    }

    private Map<String, Object> load(UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.original_filename, t.name ->> 'de' as de, t.name ->> 'en' as en, t.name ->> 'ru' as ru,
                       t.category, c.confidence, p.display_name as party, s.priority,
                       k.title as task_title, k.due_at, cs.reference as case_reference, cs.title as case_title,
                       (select f.field_value from document_extractions f where f.document_id = d.id
                          and f.field_key in ('Betrag', 'Gesamtbetrag') order by f.created_at limit 1) as amount
                from documents d
                left join document_types t on t.id = d.document_type_id
                left join document_classifications c on c.document_id = d.id and c.is_current
                left join contacts p on p.id = d.mandant_id
                left join document_type_scenarios s on s.document_type_code = t.code and s.tenant_id is null
                left join tasks k on k.source_document_id = d.id and k.source = 'DOCUMENT' and k.deleted_at is null
                left join cases cs on cs.id = k.case_id
                where d.id = ? and d.deleted_at is null
                limit 1
                """, documentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String processedText(String locale, Map<String, Object> f, String result, String priority, boolean waiting) {
        List<String> lines = new ArrayList<>();
        lines.add((waiting ? "❓ " : "📄 ") + f.get("original_filename"));
        int i = TelegramTexts.index(locale);
        String type = i == 1 ? (String) f.get("en") : i == 2 ? (String) f.get("ru") : (String) f.get("de");
        if (type == null) {
            type = (String) f.get("de");
        }
        if (type == null) {
            lines.add(TelegramTexts.t(locale, "typeUnknown"));
        }
        else {
            String confidence = f.get("confidence") instanceof BigDecimal c ? " (" + Math.round(c.doubleValue() * 100) + " %)" : "";
            lines.add(TelegramTexts.t(locale, "type", type + confidence));
        }
        if (f.get("party") != null) {
            lines.add(TelegramTexts.t(locale, "addressee", f.get("party")));
        }
        lines.add(TelegramTexts.t(locale, "importance", TelegramTexts.t(locale, "priority." + priority)));
        switch (result) {
            case "TASK_CREATED" -> {
                lines.add(TelegramTexts.t(locale, "done.TASK_CREATED", f.get("task_title")));
                if (f.get("due_at") instanceof java.sql.Timestamp due) {
                    lines.add(TelegramTexts.t(locale, "due", due.toLocalDateTime().toLocalDate()));
                }
                if (f.get("amount") != null) {
                    lines.add(TelegramTexts.t(locale, "amount", f.get("amount")));
                }
            }
            case "FILE_ONLY", "ARCHIVED_OLD" -> lines.add(TelegramTexts.t(locale, "done." + result));
            case "SKIPPED" -> lines.add(TelegramTexts.t(locale, "done.WAITING"));
            default -> lines.add(TelegramTexts.t(locale, "done.FILE_ONLY"));
        }
        String category = f.get("category") == null ? "UNSORTED" : String.valueOf(f.get("category"));
        lines.add(TelegramTexts.t(locale, "filed", TelegramTexts.t(locale, "category." + category)));
        if (f.get("case_reference") != null || f.get("case_title") != null) {
            lines.add(TelegramTexts.t(locale, "case", f.get("case_reference") != null
                    ? f.get("case_reference") + " " + (f.get("case_title") == null ? "" : f.get("case_title"))
                    : f.get("case_title")));
        }
        if (!publicBaseUrl.isBlank()) {
            lines.add(TelegramTexts.t(locale, "open", publicBaseUrl + (waiting ? "/review" : "/documents")));
        }
        return String.join("\n", lines);
    }

    /**
     * What the owners are told in the platform is passed on, in the person's language: what needs a decision, what is
     * overdue, what was stopped. {@code recipients} limits it to those people (empty: every linked person).
     */
    public void mirror(UUID tenantId, List<UUID> recipients, String type, Map<String, Object> facts) {
        if (!telegram.isConfigured()) {
            return;
        }
        try {
            String task = String.valueOf(facts.getOrDefault("taskTitle", ""));
            String file = String.valueOf(facts.getOrDefault("filename", ""));
            switch (type) {
                case "task.overdue" -> telegram.inform(tenantId, recipients, true,
                        locale -> TelegramTexts.t(locale, "overdue", task));
                case "task.reminder" -> telegram.inform(tenantId, recipients, true,
                        locale -> TelegramTexts.t(locale, "reminder", task));
                case "task.unassigned" -> telegram.inform(tenantId, recipients, true,
                        locale -> TelegramTexts.t(locale, "unassigned", task));
                case "document.review_required" -> telegram.inform(tenantId, recipients, true, locale -> {
                    String kind = String.valueOf(facts.getOrDefault("reviewKind", "NO_MATCH"));
                    String why = "LOW_CONFIDENCE".equals(kind)
                            ? TelegramTexts.t(locale, "reviewKind.LOW_CONFIDENCE", facts.getOrDefault("confidence", 0))
                            : TelegramTexts.t(locale, "reviewKind." + kind);
                    return TelegramTexts.t(locale, "review", file) + "\n" + why
                            + (publicBaseUrl.isBlank() ? "" : "\n" + TelegramTexts.t(locale, "open", publicBaseUrl + "/review"));
                });
                case "document.processing_stopped" -> telegram.inform(tenantId, recipients, true,
                        locale -> TelegramTexts.t(locale, "stoppedDoc", file));
                case "document.duplicate_detected" -> telegram.inform(tenantId, recipients, false,
                        locale -> TelegramTexts.t(locale, "duplicate", file));
                default -> {
                    // Other kinds stay in the platform.
                }
            }
        }
        catch (RuntimeException ex) {
            log.warn("Telegram: a message of kind {} could not be passed on: {}", type, ex.getMessage());
        }
    }
}

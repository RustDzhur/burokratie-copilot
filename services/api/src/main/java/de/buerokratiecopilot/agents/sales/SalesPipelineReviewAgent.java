package de.buerokratiecopilot.agents.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code sales-pipeline-review} — Vertriebs-Pipeline-Review.
 *
 * <p>Reads the open deals from {@code cases.metadata.deal} and finds the four things a weekly
 * pipeline conversation is about: a deal that has not moved for the configured number of weeks, a
 * deal without a next step, a close date in the past, and a deal whose amount is unknown. Each deal
 * gets one recommendation; the whole review becomes one task for the sales manager.</p>
 *
 * <p>Nothing in the CRM is changed: the review is evidence for a conversation, not an automatic
 * stage change.</p>
 */
@Component
public class SalesPipelineReviewAgent implements Agent {

    static final int DEFAULT_PERIOD_WEEKS = 4;
    private static final int MAX_DEALS = 1000;
    private static final int MAX_PRODUCED = 50;

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;
    private final ObjectMapper mapper;

    public SalesPipelineReviewAgent(JdbcTemplate jdbc, AgentTaskFactory tasks, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "sales-pipeline-review";
    }

    /** The facts of one open deal that the assessment needs. */
    public record DealRow(UUID caseId, String title, String stage, Instant lastMovedAt,
                          String nextStep, LocalDate expectedCloseDate, BigDecimal amount,
                          String currency, UUID ownerUserId) {
    }

    /** One recommendation about one deal. */
    public record Finding(UUID caseId, String title, String stage, String code,
                          String recommendation, BigDecimal amount) {
    }

    /** What the review found. */
    public record Review(int deals, List<Finding> findings) {

        public long count(String code) {
            return findings.stream().filter(finding -> finding.code().equals(code)).count();
        }
    }

    @Override
    public AgentResult run(AgentContext context) {
        int weeks = integer(context.input("periodWeeks"), DEFAULT_PERIOD_WEEKS);
        if (weeks < 1 || weeks > 52) {
            return AgentResult.failed("periodWeeks muss zwischen 1 und 52 liegen.");
        }
        UUID ownerFilter = null;
        if (clean(context.input("ownerUserId")) != null) {
            try {
                ownerFilter = UUID.fromString(clean(context.input("ownerUserId")));
            }
            catch (IllegalArgumentException ex) {
                return AgentResult.failed("ownerUserId ist keine gültige UUID.");
            }
        }
        BigDecimal minAmount = decimal(context.input("minAmount"));
        Set<String> stageFilter = split(context.input("stages"));

        List<Map<String, Object>> raw = jdbc.queryForList("""
                select c.id, c.title, c.owner_user_id, c.updated_at, c.metadata::text as metadata
                from cases c
                where c.tenant_id = ? and c.deleted_at is null
                  and c.metadata -> 'deal' is not null
                order by c.updated_at desc
                limit ?
                """, context.tenantId(), MAX_DEALS);

        List<DealRow> deals = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Map<String, Object> metadata = parseMap(string(row.get("metadata")));
            Map<String, Object> deal = asMap(metadata.get("deal"));
            String stage = clean(string(deal.get("stage")));
            if (stage == null || "WON".equalsIgnoreCase(stage) || "LOST".equalsIgnoreCase(stage)) {
                continue;
            }
            stage = stage.toUpperCase(Locale.ROOT);
            if (stageFilter != null && !stageFilter.contains(stage)) {
                continue;
            }
            UUID owner = (UUID) row.get("owner_user_id");
            if (ownerFilter != null && !ownerFilter.equals(owner)) {
                continue;
            }
            BigDecimal amount = decimal(deal.get("amount"));
            if (amount == null) {
                amount = decimal(metadata.get("amount"));
            }
            if (minAmount != null && amount != null && amount.compareTo(minAmount) < 0) {
                continue;
            }
            deals.add(new DealRow((UUID) row.get("id"), string(row.get("title")), stage,
                    instant(deal.get("lastMovedAt")) != null ? instant(deal.get("lastMovedAt"))
                            : instant(row.get("updated_at")),
                    clean(string(deal.get("nextStep"))), date(deal.get("expectedCloseDate")),
                    amount, clean(string(deal.get("currency"))), owner));
        }

        if (deals.isEmpty()) {
            return AgentResult.skipped("Keine offenen Deals im Filter gefunden; es gibt keine "
                    + "Pipeline zu prüfen.");
        }
        Review review = assess(deals, context.now(), weeks);
        if (review.findings().isEmpty()) {
            return AgentResult.done("Pipeline-Review: " + review.deals() + " offene Deals geprüft, "
                    + "keine Auffälligkeit (kein Stillstand, nächster Schritt vorhanden, "
                    + "Abschlussdaten plausibel).", List.of(), Map.of("deals",
                    String.valueOf(review.deals()), "findings", "0"));
        }

        String title = "Vertriebs-Pipeline-Review KW "
                + context.now().atZone(ZoneOffset.UTC).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                + "/" + context.now().atZone(ZoneOffset.UTC).get(IsoFields.WEEK_BASED_YEAR);
        String description = description(review, weeks);
        Task.Priority priority = review.findings().stream().anyMatch(
                finding -> "STALLED".equals(finding.code())
                        || "CLOSE_DATE_OVERDUE".equals(finding.code()))
                ? Task.Priority.HIGH : Task.Priority.NORMAL;
        AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                context.actorUserId(), title, description, priority,
                "Offene Deals mit Stillstand, fehlendem nächsten Schritt oder unplausiblem "
                        + "Abschlussdatum; der Review-Bericht ist die Grundlage für das Gespräch.",
                context.now().plus(3, ChronoUnit.DAYS),
                List.of("sales", "pipeline-review"));

        for (Finding finding : review.findings().stream().limit(MAX_PRODUCED).toList()) {
            context.addProduced("pipeline-finding", finding.caseId().toString(),
                    finding.code() + ": " + finding.title() + " — " + finding.recommendation());
        }
        context.addProduced("task", task.taskId().toString(), title);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("deals", String.valueOf(review.deals()));
        meta.put("findings", String.valueOf(review.findings().size()));
        meta.put("stalled", String.valueOf(review.count("STALLED")));
        meta.put("missingNextStep", String.valueOf(review.count("NO_NEXT_STEP")));
        meta.put("overdueClose", String.valueOf(review.count("CLOSE_DATE_OVERDUE")));
        meta.put("amountMissing", String.valueOf(review.count("AMOUNT_MISSING")));
        meta.put("periodWeeks", String.valueOf(weeks));
        meta.put("taskId", task.taskId().toString());
        meta.put("taskCreated", String.valueOf(task.created()));
        meta.put("limitation", "Deal-Betrag und Abschlussdatum stammen aus dem CRM-Metadatum; "
                + "fehlende Werte werden als Lücke gemeldet, nicht geschätzt.");

        return AgentResult.done("Pipeline-Review: " + review.deals() + " offene Deals geprüft, "
                        + review.findings().size() + " Auffälligkeit(en): "
                        + review.count("STALLED") + " Stillstand, "
                        + review.count("NO_NEXT_STEP") + " ohne nächsten Schritt, "
                        + review.count("CLOSE_DATE_OVERDUE") + " überfälliger Abschluss.",
                context.produced(), meta);
    }

    /**
     * Assesses open deals. Visible for tests. A deal gets at most one recommendation; the first
     * applicable rule wins in the order stall, missing next step, overdue close, missing amount.
     */
    public static Review assess(List<DealRow> deals, Instant now, int periodWeeks) {
        List<Finding> findings = new ArrayList<>();
        long stallDays = Math.max(1, periodWeeks) * 7L;
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        for (DealRow deal : deals) {
            if (deal.lastMovedAt() != null
                    && ChronoUnit.DAYS.between(deal.lastMovedAt(), now) >= stallDays) {
                findings.add(new Finding(deal.caseId(), deal.title(), deal.stage(), "STALLED",
                        "Nachfassen: seit " + ChronoUnit.DAYS.between(deal.lastMovedAt(), now)
                                + " Tagen keine Bewegung", deal.amount()));
                continue;
            }
            if (deal.nextStep() == null) {
                findings.add(new Finding(deal.caseId(), deal.title(), deal.stage(), "NO_NEXT_STEP",
                        "Nächsten Schritt mit Termin festlegen", deal.amount()));
                continue;
            }
            if (deal.expectedCloseDate() != null && deal.expectedCloseDate().isBefore(today)) {
                findings.add(new Finding(deal.caseId(), deal.title(), deal.stage(),
                        "CLOSE_DATE_OVERDUE",
                        "Abschlussdatum " + deal.expectedCloseDate()
                                + " ist überschritten: verschieben oder schließen",
                        deal.amount()));
                continue;
            }
            if (deal.amount() == null) {
                findings.add(new Finding(deal.caseId(), deal.title(), deal.stage(), "AMOUNT_MISSING",
                        "Deal-Betrag fehlt; ohne Betrag ist die Pipeline-Summe unvollständig",
                        null));
            }
        }
        return new Review(deals.size(), List.copyOf(findings));
    }

    private static String description(Review review, int weeks) {
        StringBuilder text = new StringBuilder();
        text.append("Pipeline-Review über ").append(weeks).append(" Wochen. ");
        text.append("Der Agent ändert keine Stufe und keinen Betrag.\n\n");
        text.append("Auffälligkeiten:\n");
        for (Finding finding : review.findings().stream().limit(MAX_PRODUCED).toList()) {
            text.append("- [").append(finding.code()).append("] ").append(finding.title())
                    .append(" (").append(finding.stage()).append("): ")
                    .append(finding.recommendation());
            if (finding.amount() != null) {
                text.append(" [Betrag ").append(finding.amount().toPlainString()).append("]");
            }
            text.append('\n');
        }
        text.append('\n').append("Bitte im wöchentlichen Vertriebsgespräch entscheiden, ")
                .append("welche Deals nachgefasst, verschoben, eskaliert oder geschlossen werden.");
        return text.toString();
    }

    private Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        }
        catch (Exception ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static Instant instant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (raw instanceof java.time.OffsetDateTime value) {
            return value.toInstant();
        }
        if (raw instanceof Instant value) {
            return value;
        }
        String text = String.valueOf(raw);
        try {
            return Instant.parse(text);
        }
        catch (java.time.DateTimeException ignored) {
            // fall through to date-only values
        }
        try {
            return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        catch (java.time.DateTimeException ex) {
            return null;
        }
    }

    private static LocalDate date(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate value) {
            return value;
        }
        if (raw instanceof java.sql.Date value) {
            return value.toLocalDate();
        }
        if (raw instanceof Instant value) {
            return value.atZone(ZoneOffset.UTC).toLocalDate();
        }
        String text = String.valueOf(raw);
        try {
            return LocalDate.parse(text);
        }
        catch (java.time.DateTimeException ex) {
            Instant parsed = instant(raw);
            return parsed == null ? null : parsed.atZone(ZoneOffset.UTC).toLocalDate();
        }
    }

    private static int integer(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.strip().replace(',', '.'));
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal decimal(Object raw) {
        if (raw instanceof BigDecimal value) {
            return value;
        }
        if (raw instanceof Number value) {
            return new BigDecimal(value.toString());
        }
        return raw == null ? null : decimal(String.valueOf(raw));
    }

    private static Set<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                values.add(part.strip().toUpperCase(Locale.ROOT));
            }
        }
        return values.isEmpty() ? null : values;
    }

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

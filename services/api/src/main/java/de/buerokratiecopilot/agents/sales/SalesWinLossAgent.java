package de.buerokratiecopilot.agents.sales;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * {@code sales-win-loss} — Gewonnen-Verloren-Analyse.
 *
 * <p>Reads the deals the CRM keeps on cases ({@code metadata.deal}) and reports won/lost counts,
 * win rate and the stated reasons. A deal without a reason is reported as
 * {@code ohne Angabe} — the analysis never invents a cause, because an invented reason would
 * lead to the wrong improvement.</p>
 *
 * <p>The analysis is descriptive. It does not forecast and changes no deal.</p>
 */
@Component
public class SalesWinLossAgent implements Agent {

    private static final int MAX_DEALS = 1000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SalesWinLossAgent(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "sales-win-loss";
    }

    /** One closed deal as it is analysed. */
    public record DealRow(UUID id, String title, String stage, String reason, String segment,
                          Instant closedAt) {
    }

    /** The aggregate the report consists of. */
    public record Analysis(int won, int lost, int withoutReason, List<ReasonCount> winReasons,
                           List<ReasonCount> lostReasons, List<SegmentCount> segments) {

        public int total() {
            return won + lost;
        }

        public String winRate() {
            return total() == 0 ? "0"
                    : String.format(Locale.ROOT, "%.1f", won * 100.0 / total());
        }
    }

    public record ReasonCount(String reason, int count) {
    }

    public record SegmentCount(String segment, int won, int lost) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(require(context.input("periodFrom")));
            to = LocalDate.parse(require(context.input("periodTo")));
        }
        catch (RuntimeException ex) {
            return AgentResult.skipped("periodFrom und periodTo im Format YYYY-MM-DD sind "
                    + "erforderlich; ohne Zeitraum keine Auswertung.");
        }
        if (to.isBefore(from)) {
            return AgentResult.failed("Das Ende liegt vor dem Anfang.");
        }
        boolean includeLostReasons = !"false".equalsIgnoreCase(
                context.input("includeLostReasons"));
        String segmentFilter = clean(context.input("segment"));

        List<Map<String, Object>> raw = jdbc.queryForList("""
                select c.id, c.title, c.metadata::text as metadata,
                       c.updated_at, c.closed_at,
                       ct.organization_name, ct.display_name,
                       ct.metadata::text as contact_metadata
                from cases c
                left join contacts ct on ct.id = c.counterparty_contact_id
                where c.tenant_id = ? and c.deleted_at is null
                  and c.metadata -> 'deal' ->> 'stage' in ('WON', 'LOST')
                order by c.updated_at desc
                limit ?
                """, context.tenantId(), MAX_DEALS);

        List<DealRow> deals = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Map<String, Object> deal = parseMap(string(row.get("metadata")));
            Map<String, Object> dealData = asMap(deal.get("deal"));
            String stage = clean(string(dealData.get("stage")));
            if (!"WON".equalsIgnoreCase(stage) && !"LOST".equalsIgnoreCase(stage)) {
                continue;
            }
            Instant closedAt = closedAt(dealData, row);
            if (closedAt == null) {
                continue;
            }
            LocalDate day = closedAt.atZone(ZoneOffset.UTC).toLocalDate();
            if (day.isBefore(from) || day.isAfter(to)) {
                continue;
            }
            String segment = segment(dealData, row);
            if (segmentFilter != null
                    && (segment == null || !segment.toLowerCase(Locale.ROOT)
                            .contains(segmentFilter.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            String reason = clean(string(dealData.get("winReason")));
            if (reason == null) {
                reason = clean(string(dealData.get("lostReason")));
            }
            if (reason == null) {
                reason = clean(string(dealData.get("reason")));
            }
            if (reason == null) {
                reason = clean(string(dealData.get("lastEvent")));
            }
            if ("LOST".equalsIgnoreCase(stage) && !includeLostReasons) {
                reason = null;
            }
            deals.add(new DealRow((UUID) row.get("id"), string(row.get("title")),
                    stage.toUpperCase(Locale.ROOT), reason, segment, closedAt));
        }

        if (deals.isEmpty()) {
            return AgentResult.skipped("Keine abgeschlossenen Deals im Zeitraum " + from + " bis "
                    + to + (segmentFilter == null ? "" : " (Segment " + segmentFilter + ")")
                    + " gefunden.");
        }
        Analysis analysis = analyse(deals);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("periodFrom", from.toString());
        meta.put("periodTo", to.toString());
        meta.put("deals", String.valueOf(deals.size()));
        meta.put("won", String.valueOf(analysis.won()));
        meta.put("lost", String.valueOf(analysis.lost()));
        meta.put("winRatePercent", analysis.winRate());
        meta.put("withoutReason", String.valueOf(analysis.withoutReason()));
        meta.put("winReasons", reasons(analysis.winReasons()));
        meta.put("lostReasons", reasons(analysis.lostReasons()));
        meta.put("segments", String.valueOf(analysis.segments().size()));
        meta.put("includeLostReasons", String.valueOf(includeLostReasons));
        meta.put("limitation", "Die Auswertung beschreibt die im CRM hinterlegten Gründe; "
                + "fehlende Gründe werden nicht geschätzt.");

        context.addProduced("win-loss-report", from + ":" + to,
                analysis.won() + " gewonnen, " + analysis.lost() + " verloren, Siegquote "
                        + analysis.winRate() + " %");
        for (ReasonCount reason : analysis.lostReasons().stream().limit(10).toList()) {
            context.addProduced("lost-reason", reason.reason(), reason.count() + " Deal(s)");
        }
        for (ReasonCount reason : analysis.winReasons().stream().limit(10).toList()) {
            context.addProduced("win-reason", reason.reason(), reason.count() + " Deal(s)");
        }

        String summary = "Gewonnen-Verloren-Analyse " + from + " bis " + to + ": "
                + analysis.won() + " gewonnen, " + analysis.lost() + " verloren (Siegquote "
                + analysis.winRate() + " %), " + analysis.withoutReason()
                + " ohne Grundangabe. Häufigste Verlustgründe: "
                + topReasons(analysis.lostReasons()) + ".";
        return AgentResult.done(summary, context.produced(), meta);
    }

    /** Aggregates closed deals. Visible for tests. */
    public static Analysis analyse(List<DealRow> deals) {
        int won = 0;
        int lost = 0;
        int withoutReason = 0;
        Map<String, Integer> winReasons = new LinkedHashMap<>();
        Map<String, Integer> lostReasons = new LinkedHashMap<>();
        Map<String, int[]> segments = new LinkedHashMap<>();

        for (DealRow deal : deals) {
            boolean isWon = "WON".equalsIgnoreCase(deal.stage());
            if (isWon) {
                won++;
            }
            else {
                lost++;
            }
            String reason = clean(deal.reason());
            if (reason == null) {
                withoutReason++;
                reason = "ohne Angabe";
            }
            if (isWon) {
                winReasons.merge(reason, 1, Integer::sum);
            }
            else {
                lostReasons.merge(reason, 1, Integer::sum);
            }
            String segment = clean(deal.segment());
            if (segment != null) {
                int[] counts = segments.computeIfAbsent(segment, ignored -> new int[2]);
                if (isWon) {
                    counts[0]++;
                }
                else {
                    counts[1]++;
                }
            }
        }
        return new Analysis(won, lost, withoutReason, sorted(winReasons), sorted(lostReasons),
                segments.entrySet().stream()
                        .sorted(Map.Entry.<String, int[]>comparingByValue(
                                Comparator.comparingInt(value -> value[0] + value[1])).reversed())
                        .map(entry -> new SegmentCount(entry.getKey(), entry.getValue()[0],
                                entry.getValue()[1]))
                        .toList());
    }

    private static List<ReasonCount> sorted(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new ReasonCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String reasons(List<ReasonCount> reasons) {
        return reasons.stream().limit(5)
                .map(reason -> reason.reason() + " (" + reason.count() + ")")
                .reduce((left, right) -> left + "; " + right).orElse("");
    }

    private static String topReasons(List<ReasonCount> reasons) {
        return reasons.stream().limit(3)
                .map(reason -> reason.reason() + " (" + reason.count() + ")")
                .reduce((left, right) -> left + ", " + right).orElse("keine Angabe");
    }

    private Instant closedAt(Map<String, Object> deal, Map<String, Object> row) {
        for (String key : List.of("wonAt", "lostAt", "lastMovedAt", "closedAt")) {
            Instant parsed = instant(deal.get(key));
            if (parsed != null) {
                return parsed;
            }
        }
        Instant parsed = instant(row.get("closed_at"));
        return parsed != null ? parsed : instant(row.get("updated_at"));
    }

    private String segment(Map<String, Object> deal, Map<String, Object> row) {
        String fromDeal = clean(string(deal.get("segment")));
        if (fromDeal != null) {
            return fromDeal;
        }
        Map<String, Object> contact = parseMap(string(row.get("contact_metadata")));
        String industry = clean(string(contact.get("industry")));
        if (industry != null) {
            return industry;
        }
        String organization = clean(string(row.get("organization_name")));
        return organization != null ? organization : clean(string(row.get("display_name")));
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

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing");
        }
        return value.strip();
    }
}

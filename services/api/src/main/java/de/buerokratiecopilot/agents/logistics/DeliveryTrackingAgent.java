package de.buerokratiecopilot.agents.logistics;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * {@code logistics-delivery-tracking} — Lieferverfolgung.
 *
 * <p>Looks at the purchase orders that are still open and whose expected delivery date lies inside
 * the horizon, and separates overdue from due-soon. The state is computed from two dates and a
 * threshold, so it is a pure function and can be tested; the agent changes no order and sends no
 * reminder.</p>
 */
@Component
public class DeliveryTrackingAgent implements Agent {

    static final int DEFAULT_HORIZON_DAYS = 14;
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final int MAX_PRODUCED = 50;

    /** How a delivery date reads relative to today. */
    public enum State {
        OVERDUE, DUE_TODAY, DUE_SOON
    }

    /** One open order as read from the database. */
    public record DeliveryRow(UUID id, String poNumber, String supplier, LocalDate expected,
                              String status, BigDecimal grossAmount) {
    }

    public record Delivery(UUID id, String poNumber, String supplier, LocalDate expected,
                           long daysLeft, State state) {
    }

    private final JdbcTemplate jdbc;

    public DeliveryTrackingAgent(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "logistics-delivery-tracking";
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate today = LocalDate.ofInstant(context.now(), BERLIN);
        int horizonDays = parseHorizon(context.input("horizonDays"));
        int minDelayDays = Math.max(1, parseHorizon(context.input("minDelayDays")));
        LocalDate horizonEnd = today.plusDays(horizonDays);
        UUID supplierId = null;
        if (context.input("supplierId") != null && !context.input("supplierId").isBlank()) {
            try {
                supplierId = UUID.fromString(context.input("supplierId").strip());
            } catch (IllegalArgumentException e) {
                return AgentResult.failed("Die Lieferanten-ID ist keine gültige UUID.");
            }
        }
        boolean includePartial = !"false".equalsIgnoreCase(context.input("includePartial"));

        String statuses = includePartial
                ? "('OPEN', 'PARTIALLY_DELIVERED')" : "('OPEN')";
        String sql = """
                select po.id, po.po_number, po.expected_delivery_date, po.status,
                       po.gross_amount, s.name as supplier_name
                from purchase_orders po
                left join suppliers s on s.id = po.supplier_id
                where po.tenant_id = ? and po.deleted_at is null
                  and po.status in %s
                  and po.expected_delivery_date is not null
                  and po.expected_delivery_date <= ?
                """.formatted(statuses);
        List<DeliveryRow> rows;
        if (supplierId == null) {
            rows = jdbc.query(sql + " order by po.expected_delivery_date",
                    (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class),
                            rs.getString("po_number"), rs.getString("supplier_name"),
                            rs.getDate("expected_delivery_date").toLocalDate(),
                            rs.getString("status"), rs.getBigDecimal("gross_amount")),
                    context.tenantId(), Date.valueOf(horizonEnd));
        } else {
            rows = jdbc.query(sql + " and po.supplier_id = ? order by po.expected_delivery_date",
                    (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class),
                            rs.getString("po_number"), rs.getString("supplier_name"),
                            rs.getDate("expected_delivery_date").toLocalDate(),
                            rs.getString("status"), rs.getBigDecimal("gross_amount")),
                    context.tenantId(), Date.valueOf(horizonEnd), supplierId);
        }

        List<Delivery> deliveries = assess(rows, today, minDelayDays);
        if (deliveries.isEmpty()) {
            return AgentResult.skipped("Im Zeitfenster bis " + horizonEnd + " ist keine offene "
                    + "Bestellung überfällig oder kurzfristig fällig.");
        }

        for (Delivery delivery : deliveries.stream().limit(MAX_PRODUCED).toList()) {
            String state = switch (delivery.state()) {
                case OVERDUE -> "überfällig seit " + Math.abs(delivery.daysLeft()) + " Tag(en)";
                case DUE_TODAY -> "heute fällig";
                case DUE_SOON -> "fällig in " + delivery.daysLeft() + " Tag(en)";
            };
            context.addProduced("purchase-order", delivery.id().toString(),
                    delivery.poNumber() + (delivery.supplier() == null ? ""
                            : " / " + delivery.supplier()) + " — " + state
                            + " (Termin " + delivery.expected() + ")");
        }

        long overdue = deliveries.stream().filter(d -> d.state() == State.OVERDUE).count();
        long dueToday = deliveries.stream().filter(d -> d.state() == State.DUE_TODAY).count();
        long dueSoon = deliveries.stream().filter(d -> d.state() == State.DUE_SOON).count();
        Map<String, String> meta = Map.of(
                "horizonDays", String.valueOf(horizonDays),
                "minDelayDays", String.valueOf(minDelayDays),
                "overdue", String.valueOf(overdue),
                "dueToday", String.valueOf(dueToday),
                "dueSoon", String.valueOf(dueSoon));
        return AgentResult.done(overdue + " überfällige, " + dueToday + " heute fällige und "
                        + dueSoon + " kurzfristig fällige Bestellung(en) bis " + horizonEnd + ".",
                context.produced(), meta);
    }

    /**
     * Classifies open orders. An overdue order below the delay threshold is not reported yet — a
     * one-day slip on the delivery morning is not a finding. Visible for tests.
     */
    public static List<Delivery> assess(List<DeliveryRow> rows, LocalDate today, int minDelayDays) {
        List<Delivery> deliveries = new ArrayList<>();
        for (DeliveryRow row : rows) {
            if (row.expected() == null) {
                continue;
            }
            long daysLeft = ChronoUnit.DAYS.between(today, row.expected());
            if (daysLeft < 0 && Math.abs(daysLeft) < minDelayDays) {
                continue;
            }
            State state = daysLeft < 0 ? State.OVERDUE
                    : daysLeft == 0 ? State.DUE_TODAY : State.DUE_SOON;
            deliveries.add(new Delivery(row.id(), row.poNumber(), row.supplier(), row.expected(),
                    daysLeft, state));
        }
        deliveries.sort(Comparator.comparing(Delivery::expected));
        return List.copyOf(deliveries);
    }

    static int parseHorizon(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_HORIZON_DAYS;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return value < 0 || value > 365 ? DEFAULT_HORIZON_DAYS : value;
        } catch (NumberFormatException e) {
            return DEFAULT_HORIZON_DAYS;
        }
    }
}

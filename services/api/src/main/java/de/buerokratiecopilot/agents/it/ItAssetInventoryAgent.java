package de.buerokratiecopilot.agents.it;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code it-asset-inventory} — Asset-Inventar.
 *
 * <p>The IT assets the platform knows are registered devices and the licence-like contracts
 * ({@code SAAS}, {@code SUBSCRIPTION}). The agent finds devices without a responsible person,
 * devices that have been waiting for activation too long, devices that have been offline for
 * months, errors, and licences whose end or notice date is close.</p>
 *
 * <p>It procures nothing and changes no contract. Warranty is not a field on devices, so the agent
 * says that instead of inventing a warranty status.</p>
 */
@Component
public class ItAssetInventoryAgent implements Agent {

    private static final int MAX_FINDINGS = 100;
    private static final int PENDING_DAYS = 30;
    private static final int OFFLINE_DAYS = 90;
    private static final int LICENSE_WARN_DAYS = 60;
    private static final int NOTICE_WARN_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;

    public ItAssetInventoryAgent(JdbcTemplate jdbc, AgentTaskFactory tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "it-asset-inventory";
    }

    public record DeviceRow(UUID id, String identifier, String name, String type, String status,
                            UUID registeredBy, Instant registeredAt, Instant lastSeen,
                            String lastError) {
    }

    public record LicenseRow(UUID id, String title, String type, String status, LocalDate endDate,
                             LocalDate noticeDeadline, java.math.BigDecimal costAmount) {
    }

    public record Finding(String code, String subject, String message) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        boolean includeLicenses = !"false".equalsIgnoreCase(context.input("includeLicenses"));
        boolean includeWarranty = !"false".equalsIgnoreCase(context.input("includeWarranty"));
        String inventorySource = clean(context.input("inventorySource"));
        String locationId = clean(context.input("locationId"));

        List<DeviceRow> devices = jdbc.query("""
                select id, identifier, name, device_type, status, registered_by, registered_at,
                       last_seen_at, last_error
                from devices
                where tenant_id = ?
                order by name
                """, (rs, row) -> new DeviceRow(rs.getObject("id", UUID.class),
                        rs.getString("identifier"), rs.getString("name"),
                        rs.getString("device_type"), rs.getString("status"),
                        rs.getObject("registered_by", UUID.class),
                        rs.getTimestamp("registered_at") == null ? null
                                : rs.getTimestamp("registered_at").toInstant(),
                        rs.getTimestamp("last_seen_at") == null ? null
                                : rs.getTimestamp("last_seen_at").toInstant(),
                        rs.getString("last_error")), context.tenantId());

        List<LicenseRow> licenses = includeLicenses ? jdbc.query("""
                select id, title, contract_type, status, end_date, notice_deadline_date, cost_amount
                from contracts
                where tenant_id = ? and deleted_at is null
                  and contract_type in ('SAAS', 'SUBSCRIPTION')
                  and status in ('ACTIVE', 'PENDING_TERMINATION')
                order by end_date nulls last
                """, (rs, row) -> new LicenseRow(rs.getObject("id", UUID.class),
                        rs.getString("title"), rs.getString("contract_type"), rs.getString("status"),
                        rs.getDate("end_date") == null ? null
                                : rs.getDate("end_date").toLocalDate(),
                        rs.getDate("notice_deadline_date") == null ? null
                                : rs.getDate("notice_deadline_date").toLocalDate(),
                        rs.getBigDecimal("cost_amount")), context.tenantId()) : List.of();

        if (devices.isEmpty() && licenses.isEmpty()) {
            return AgentResult.skipped("Keine IT-Assets gefunden: keine Geräte und"
                    + (includeLicenses ? " keine SAAS-/Abo-Verträge" : " (Lizenzen nicht "
                            + "eingeschlossen)") + ".");
        }

        LocalDate today = LocalDate.ofInstant(context.now(), ZoneId.of("Europe/Berlin"));
        List<Finding> findings = new ArrayList<>();
        for (DeviceRow device : devices) {
            addDeviceFindings(device, context.now(), today, findings);
        }
        for (LicenseRow license : licenses) {
            addLicenseFindings(license, today, findings);
        }
        findings.sort(Comparator.comparing(Finding::code).thenComparing(Finding::subject));

        UUID taskId = null;
        boolean taskCreated = false;
        if (!findings.isEmpty()) {
            AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                    context.actorUserId(), "IT-Asset-Inventar: " + findings.size() + " Lücken",
                    description(findings), Task.Priority.HIGH,
                    "Fehlende Verantwortlichkeit, veraltete Geräte oder auslaufende Lizenzen; "
                            + "Aufräumen und Zuordnen ist eine Entscheidung, keine Automatik.",
                    context.now().plus(14, ChronoUnit.DAYS),
                    List.of("it", "asset-inventory"));
            taskId = task.taskId();
            taskCreated = task.created();
        }

        for (Finding finding : findings.stream().limit(MAX_FINDINGS).toList()) {
            context.addProduced("asset-finding", finding.subject(),
                    finding.code() + ": " + finding.message());
        }
        if (taskId != null) {
            context.addProduced("task", taskId.toString(), "IT-Asset-Inventar");
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("devices", String.valueOf(devices.size()));
        meta.put("licenses", String.valueOf(licenses.size()));
        meta.put("findings", String.valueOf(findings.size()));
        meta.put("noOwner", String.valueOf(count(findings, "NO_OWNER")));
        meta.put("pendingTooLong", String.valueOf(count(findings, "PENDING_TOO_LONG")));
        meta.put("offlineStale", String.valueOf(count(findings, "OFFLINE_STALE")));
        meta.put("licenseExpiring", String.valueOf(count(findings, "LICENSE_EXPIRING")
                + count(findings, "LICENSE_EXPIRED")));
        meta.put("noticeDue", String.valueOf(count(findings, "NOTICE_DUE")));
        meta.put("includeLicenses", String.valueOf(includeLicenses));
        meta.put("includeWarranty", String.valueOf(includeWarranty));
        meta.put("inventorySource", inventorySource == null ? "devices+contracts" : inventorySource);
        meta.put("locationId", locationId == null ? "" : locationId);
        meta.put("taskId", taskId == null ? "" : taskId.toString());
        meta.put("taskCreated", String.valueOf(taskCreated));
        meta.put("limitation", "Geräte tragen kein Garantiefeld; eine Garantieprüfung ist nicht "
                + "möglich. Standortfilter und Lizenzanzahl sind nicht als eigene Tabellen "
                + "vorhanden.");

        if (findings.isEmpty()) {
            return AgentResult.done("IT-Asset-Inventar: " + devices.size() + " Gerät(e) und "
                    + licenses.size() + " Lizenzvertrag/-verträge geprüft, keine Lücke gefunden.",
                    context.produced(), meta);
        }
        return AgentResult.done("IT-Asset-Inventar: " + devices.size() + " Gerät(e) und "
                        + licenses.size() + " Lizenzvertrag/-verträge geprüft, "
                        + findings.size() + " Lücke(n) gefunden. Keine Beschaffung, keine "
                        + "Vertragsänderung.", context.produced(), meta);
    }

    private static void addDeviceFindings(DeviceRow device, Instant now, LocalDate today,
                                          List<Finding> findings) {
        String subject = device.name() != null ? device.name()
                : device.identifier() != null ? device.identifier() : device.id().toString();
        if (device.registeredBy() == null && !"REVOKED".equalsIgnoreCase(device.status())) {
            findings.add(new Finding("NO_OWNER", subject,
                    "kein Verantwortlicher eingetragen (Status " + device.status() + ")"));
        }
        if ("PENDING".equalsIgnoreCase(device.status()) && device.registeredAt() != null
                && ChronoUnit.DAYS.between(device.registeredAt(), now) > PENDING_DAYS) {
            findings.add(new Finding("PENDING_TOO_LONG", subject,
                    "seit " + ChronoUnit.DAYS.between(device.registeredAt(), now)
                            + " Tagen auf Freigabe, nie aktiviert"));
        }
        if ("OFFLINE".equalsIgnoreCase(device.status())
                && (device.lastSeen() == null
                        || ChronoUnit.DAYS.between(device.lastSeen(), now) > OFFLINE_DAYS)) {
            findings.add(new Finding("OFFLINE_STALE", subject,
                    "offline" + (device.lastSeen() == null ? ", noch nie gesehen"
                            : " seit " + ChronoUnit.DAYS.between(device.lastSeen(), now)
                                    + " Tagen nicht gesehen")));
        }
        if ("ERROR".equalsIgnoreCase(device.status())
                || (device.lastError() != null && !device.lastError().isBlank())) {
            findings.add(new Finding("DEVICE_ERROR", subject,
                    "Fehler: " + (device.lastError() == null ? device.status()
                            : device.lastError())));
        }
    }

    private static void addLicenseFindings(LicenseRow license, LocalDate today,
                                           List<Finding> findings) {
        String subject = license.title() == null ? license.id().toString() : license.title();
        if (license.endDate() != null) {
            long days = ChronoUnit.DAYS.between(today, license.endDate());
            if (days < 0) {
                findings.add(new Finding("LICENSE_EXPIRED", subject,
                        "Lizenz/Vertrag ist seit " + Math.abs(days) + " Tag(en) abgelaufen"));
            }
            else if (days <= LICENSE_WARN_DAYS) {
                findings.add(new Finding("LICENSE_EXPIRING", subject,
                        "endet in " + days + " Tag(en) am " + license.endDate()));
            }
        }
        if (license.noticeDeadline() != null) {
            long days = ChronoUnit.DAYS.between(today, license.noticeDeadline());
            if (days >= 0 && days <= NOTICE_WARN_DAYS) {
                findings.add(new Finding("NOTICE_DUE", subject,
                        "Kündigungsfrist endet in " + days + " Tag(en) am "
                                + license.noticeDeadline()));
            }
        }
    }

    private static String description(List<Finding> findings) {
        StringBuilder text = new StringBuilder(
                "Das IT-Asset-Inventar hat folgende Lücken gefunden. Es wurde nichts beschafft "
                        + "und kein Vertrag geändert.\n\n");
        for (Finding finding : findings.stream().limit(MAX_FINDINGS).toList()) {
            text.append("- [").append(finding.code()).append("] ").append(finding.subject())
                    .append(": ").append(finding.message()).append('\n');
        }
        return text.toString();
    }

    private static long count(List<Finding> findings, String code) {
        return findings.stream().filter(finding -> finding.code().equals(code)).count();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

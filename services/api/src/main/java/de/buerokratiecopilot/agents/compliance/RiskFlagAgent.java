package de.buerokratiecopilot.agents.compliance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.radar.RadarEngine;
import de.buerokratiecopilot.radar.RadarQueryService;
import de.buerokratiecopilot.settings.RuleSettingsService;

/**
 * {@code risk-flag} — Risiko-Hinweise.
 *
 * <p>Runs the Radar rules over one document or the whole workspace. The Radar engine files the
 * findings idempotently (one finding per situation) and announces new or reopened findings to the
 * owners through the notification centre when the severity reaches the configured level — this
 * agent adds the honest run report on top: what was created, reopened, resolved, and which rules
 * failed. It never decides a finding itself; that stays a person's act.</p>
 */
@Component
public class RiskFlagAgent implements Agent {

    private final RadarEngine engine;
    private final RuleSettingsService ruleSettings;
    private final RadarQueryService radarQueries;

    public RiskFlagAgent(RadarEngine engine, RuleSettingsService ruleSettings,
                         RadarQueryService radarQueries) {
        this.engine = engine;
        this.ruleSettings = ruleSettings;
        this.radarQueries = radarQueries;
    }

    @Override
    public String id() {
        return "risk-flag";
    }

    @Override
    public AgentResult run(AgentContext context) {
        Boolean enabled = radarEnabled(context.tenantId());
        if (Boolean.FALSE.equals(enabled)) {
            return AgentResult.skipped("Bürokratie-Radar ist in den Einstellungen deaktiviert — keine Risiko-Prüfung");
        }

        RadarEngine.Report documentReport = null;
        String documentIdRaw = context.input("documentId");
        if (documentIdRaw != null && !documentIdRaw.isBlank()) {
            UUID documentId = parseUuid(documentIdRaw);
            if (documentId == null) {
                return AgentResult.skipped("documentId ist keine gültige UUID");
            }
            documentReport = engine.evaluateDocument(context.tenantId(), documentId);
        }

        boolean sweep = documentIdRaw == null || Boolean.parseBoolean(context.input("sweep"));
        RadarEngine.Report sweepReport = sweep ? engine.sweep(context.tenantId()) : null;

        int created = sum(documentReport, sweepReport, RadarEngine.Report::created);
        int reopened = sum(documentReport, sweepReport, RadarEngine.Report::reopened);
        int updated = sum(documentReport, sweepReport, RadarEngine.Report::updated);
        int unchanged = sum(documentReport, sweepReport, RadarEngine.Report::unchanged);
        int resolved = sum(documentReport, sweepReport, RadarEngine.Report::resolved);
        int failedRules = sum(documentReport, sweepReport, RadarEngine.Report::failedRules);
        int evaluated = created + reopened + updated + unchanged + resolved;

        if (failedRules > 0 && evaluated == 0) {
            return AgentResult.failed("Alle Radar-Regeln sind fehlgeschlagen (" + failedRules
                    + " Fehler) — es konnte nichts geprüft werden");
        }

        String ref = documentIdRaw == null || documentIdRaw.isBlank()
                ? context.tenantId().toString() : documentIdRaw;
        context.addProduced("riskSweep", ref,
                "Radar: " + created + " neue, " + reopened + " wiedereröffnete, " + resolved + " erledigte Befunde");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("created", String.valueOf(created));
        meta.put("reopened", String.valueOf(reopened));
        meta.put("updated", String.valueOf(updated));
        meta.put("unchanged", String.valueOf(unchanged));
        meta.put("resolved", String.valueOf(resolved));
        meta.put("failedRules", String.valueOf(failedRules));
        meta.put("sweep", String.valueOf(sweep));
        if (context.actorUserId() != null) {
            try {
                Map<String, Object> open = radarQueries.summary(context.tenantId(), context.actorUserId());
                meta.put("openBySeverity", String.valueOf(open.get("openBySeverity")));
            }
            catch (RuntimeException ex) {
                // The summary is an enrichment; the run report above already says what happened.
                meta.put("openBySeverity", "nicht verfügbar");
            }
        }

        String summary;
        if (created + reopened == 0) {
            summary = "Risiko-Prüfung abgeschlossen: keine neuen Befunde"
                    + (resolved > 0 ? ", " + resolved + " erledigt" : "")
                    + (failedRules > 0 ? ", " + failedRules + " Regeln fehlgeschlagen" : "");
        }
        else {
            summary = "Risiko-Prüfung: " + created + " neue und " + reopened
                    + " wiedereröffnete Befunde — Verantwortliche wurden benachrichtigt"
                    + (failedRules > 0 ? ", " + failedRules + " Regeln fehlgeschlagen" : "");
        }
        return AgentResult.done(summary, context.produced(), meta);
    }

    /** The effective {@code radar.enabled} value, or {@code null} when not configured (engine default: enabled). */
    private Boolean radarEnabled(UUID tenantId) {
        for (Map<String, Object> item : ruleSettings.list(tenantId)) {
            if ("radar.enabled".equals(item.get("key")) && item.get("value") instanceof Boolean enabled) {
                return enabled;
            }
        }
        return null;
    }

    private static int sum(RadarEngine.Report documentReport, RadarEngine.Report sweepReport,
                           java.util.function.ToIntFunction<RadarEngine.Report> accessor) {
        int total = 0;
        if (documentReport != null) {
            total += accessor.applyAsInt(documentReport);
        }
        if (sweepReport != null) {
            total += accessor.applyAsInt(sweepReport);
        }
        return total;
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

package de.buerokratiecopilot.agents.documents;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.devices.DeviceService;
import de.buerokratiecopilot.importer.FolderImportService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * {@code doc-scan} — Scan-Eingang.
 *
 * <p>Takes a scan order and turns it into work the platform can process: for a device, a
 * {@code SCAN} job in the device queue (the device pulls, nothing is pushed); for a folder, a real
 * folder import through the ingest module. The idempotency key ({@code sourceKey}) travels with
 * the order: a repeated call with the same key and the same device does not queue a second job,
 * and a folder import records the same source so duplicates are recognised.</p>
 */
@Component
public class DocScanAgent implements Agent {

    private final DeviceService devices;
    private final FolderImportService folderImport;
    private final JdbcTemplate jdbc;

    public DocScanAgent(DeviceService devices, FolderImportService folderImport, JdbcTemplate jdbc) {
        this.devices = devices;
        this.folderImport = folderImport;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "doc-scan";
    }

    @Override
    public AgentResult run(AgentContext context) {
        return RequestContext.runAs(context.tenantId(), () -> accept(context));
    }

    private AgentResult accept(AgentContext context) {
        String deviceIdRaw = context.input("deviceId");
        String folderPath = context.input("folderPath");
        if (isBlank(deviceIdRaw) && isBlank(folderPath)) {
            return AgentResult.skipped(
                    "Weder deviceId noch folderPath angegeben; es gibt keinen Scan-Auftrag anzunehmen.");
        }

        UUID deviceId = parseUuid(deviceIdRaw);
        if (deviceIdRaw != null && !deviceIdRaw.isBlank() && deviceId == null) {
            return AgentResult.failed("deviceId ist keine UUID: " + deviceIdRaw);
        }

        if (deviceId != null) {
            return queueDeviceJob(context, deviceId);
        }
        return importFolder(context, folderPath);
    }

    /** A device receives a queued job; a duplicate order with the same key is not queued twice. */
    private AgentResult queueDeviceJob(AgentContext context, UUID deviceId) {
        Map<String, Object> device;
        try {
            device = devices.describe(context.tenantId(), deviceId);
        }
        catch (ApiException ex) {
            return AgentResult.failed("Gerät " + deviceId + " wurde nicht gefunden.");
        }
        String status = String.valueOf(device.get("status"));
        if (!"ACTIVE".equals(status)) {
            return AgentResult.failed(
                    "Das Gerät " + deviceId + " ist im Status " + status
                            + " und erhält keine Aufträge.");
        }

        String key = sourceKey(context, deviceId.toString());
        String label = context.input("label");
        String parameters = parametersJson(key, label, context.input("folderPath"));

        Integer existing = jdbc.queryForObject("""
                select count(*) from device_jobs
                where tenant_id = ? and device_id = ?
                  and status in ('QUEUED', 'CLAIMED', 'RUNNING')
                  and parameters ->> 'sourceKey' = ?
                """, Integer.class, context.tenantId(), deviceId, key);
        if (existing != null && existing > 0) {
            return AgentResult.skipped(
                    "Für diesen Auftrag existiert bereits ein offener Scan-Job (sourceKey " + key
                            + "); es wird kein zweiter angelegt.");
        }

        UUID jobId = devices.queueJob(context.tenantId(), deviceId, "SCAN", parameters,
                context.actorUserId());
        return AgentResult.done("Scan-Auftrag angenommen: Job " + jobId + " für Gerät " + deviceId
                + " (sourceKey " + key + ").",
                List.of(new AgentResult.Produced("scan-job", jobId.toString(),
                        "Scan-Auftrag für Gerät " + deviceId)),
                Map.of("jobId", jobId.toString(), "jobType", "SCAN", "deviceId", deviceId.toString(),
                        "sourceKey", key));
    }

    /** A folder is imported through the ingest module; the source key travels into the provenance. */
    private AgentResult importFolder(AgentContext context, String folderPath) {
        String key = sourceKey(context, "folder:" + folderPath);
        try {
            FolderImportService.ImportResult result = folderImport.importFolder(context.tenantId(),
                    Path.of(folderPath), key, context.actorUserId());
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("sourceKey", key);
            meta.put("folderPath", folderPath);
            meta.put("imported", String.valueOf(result.imported()));
            meta.put("skipped", String.valueOf(result.skipped()));
            meta.put("notReady", String.valueOf(result.notReady()));
            meta.put("candidates", String.valueOf(result.candidates()));
            if (result.imported() == 0 && result.skipped() == 0) {
                return AgentResult.skipped("Ordner " + folderPath + ": keine Datei importiert ("
                        + result.notReady() + " noch nicht bereit, " + result.candidates()
                        + " Kandidaten).");
            }
            return AgentResult.done("Ordner " + folderPath + ": " + result.imported()
                    + " Datei(en) importiert, " + result.skipped() + " übersprungen.",
                    List.of(new AgentResult.Produced("scan-import", key,
                            result.imported() + " Dateien aus " + folderPath)),
                    meta);
        }
        catch (IOException ex) {
            return AgentResult.failed("Der Ordner konnte nicht eingelesen werden: " + ex.getMessage());
        }
    }

    /** The idempotency key: the given one, or a deterministic one from the order itself. */
    private String sourceKey(AgentContext context, String fallbackBasis) {
        String key = context.input("sourceKey");
        if (!isBlank(key)) {
            return key.strip();
        }
        return "scan-" + (isBlank(context.input("label")) ? fallbackBasis : context.input("label"));
    }

    private static String parametersJson(String sourceKey, String label, String folderPath) {
        List<String> parts = new ArrayList<>();
        parts.add("\"sourceKey\":\"" + escape(sourceKey) + "\"");
        if (!isBlank(label)) {
            parts.add("\"label\":\"" + escape(label) + "\"");
        }
        if (!isBlank(folderPath)) {
            parts.add("\"folderPath\":\"" + escape(folderPath) + "\"");
        }
        return "{" + String.join(",", parts) + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

package de.buerokratiecopilot.devices.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import de.buerokratiecopilot.devices.DeviceService;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The receiving end for the Desktop Bridge (spec §8.2).
 *
 * <p><strong>Why this exists.</strong> {@code DeviceService.authenticate} and {@code DeviceService.claimNext}
 * were written, tested and reachable from nothing: the platform could register a scanner and queue work for
 * it, and the scanner had no way to collect that work. The half that was missing is exactly the half that
 * makes a bridge a bridge.</p>
 *
 * <p><strong>A device authenticates with its key, not with a session.</strong> The workstation runs a program,
 * not a browser; it presents the key it was given at registration in the {@code X-Device-Key} header on every
 * call. The key is compared as a hash, so the stored form cannot be turned back into a credential.</p>
 *
 * <p><strong>The device acts inside the tenant it belongs to.</strong> Every call runs under
 * {@code RequestContext.runAs}, so row level security applies exactly as it does for a person, and a device
 * key cannot reach another account's work even if the job id is guessed.</p>
 */
@RestController
@RequestMapping("/api/v1/device-agent")
@Tag(name = "Device agent", description = "The Desktop Bridge collects work and returns scans here")
public class DeviceAgentController {

    private final DeviceService devices;
    private final DocumentService documents;

    public DeviceAgentController(DeviceService devices, DocumentService documents) {
        this.devices = devices;
        this.documents = documents;
    }

    private record Caller(UUID tenantId, UUID deviceId, String identifier, String name) {
    }

    /** Resolves the key, or answers 401 with a reason the agent can print for the person at the machine. */
    private Caller caller(String deviceKey) {
        DeviceService.Device device = devices.authenticate(deviceKey);
        return new Caller(device.tenantId(), device.deviceId(), device.identifier(), device.name());
    }

    @GetMapping("/me")
    @Operation(summary = "Who am I: checks the device key and says which device it belongs to")
    public Map<String, Object> me(@RequestHeader(value = "X-Device-Key", required = false) String deviceKey) {
        Caller caller = caller(deviceKey);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("deviceId", caller.deviceId().toString());
        answer.put("identifier", caller.identifier());
        answer.put("name", caller.name());
        return answer;
    }

    @PostMapping("/claim")
    @Operation(summary = "Claims the next queued job, or answers an empty object when there is none")
    public Map<String, Object> claim(@RequestHeader(value = "X-Device-Key", required = false) String deviceKey) {
        Caller caller = caller(deviceKey);
        // An empty answer is the normal case, not an error: a poll that finds nothing must not look like a
        // failure, or every idle minute would fill the log with red.
        Map<String, Object> job = RequestContext.runAs(caller.tenantId(),
                () -> devices.claimNext(caller.tenantId(), caller.deviceId()));
        if (job.isEmpty() || job.get("id") == null) {
            return Map.of();
        }
        // The service names the key 'id'; the wire names it 'jobId', because a job id next to a document id
        // and a device id needs the word that says which one it is.
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("jobId", String.valueOf(job.get("id")));
        answer.put("jobType", job.get("jobType"));
        answer.put("parameters", job.get("parameters"));
        answer.put("attempt", job.get("attempt"));
        answer.put("maxAttempts", job.get("maxAttempts"));
        return answer;
    }

    @PostMapping(path = "/jobs/{jobId}/result", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Delivers the scan for a claimed job, or the reason there is none")
    public ResponseEntity<Map<String, Object>> result(
            @RequestHeader(value = "X-Device-Key", required = false) String deviceKey,
            @PathVariable UUID jobId,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "errorCode", required = false) String errorCode,
            @RequestParam(value = "errorMessage", required = false) String errorMessage) {

        Caller caller = caller(deviceKey);

        if (file == null || file.isEmpty()) {
            if (errorCode == null || errorCode.isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Entweder eine Datei oder ein Fehlercode ist erforderlich");
            }
            Map<String, Object> report = RequestContext.runAs(caller.tenantId(),
                    () -> devices.reportResult(caller.tenantId(), caller.deviceId(), jobId, false,
                            errorCode, errorMessage, null));
            return ResponseEntity.ok(report);
        }

        // The scan is a document like any other: it goes through the same intake, so the security scan,
        // the hash and duplicate detection apply to a sheet from a scanner exactly as to an upload.
        DocumentService.ReceivedDocument received = RequestContext.runAs(caller.tenantId(),
                () -> documents.receive(new DocumentService.UploadRequest(file, null, null,
                        Document.Source.SCANNER, caller.name())));

        Map<String, Object> report = RequestContext.runAs(caller.tenantId(),
                () -> devices.reportResult(caller.tenantId(), caller.deviceId(), jobId, true,
                        null, null, received.document().getId()));
        Map<String, Object> answer = new LinkedHashMap<>(report);
        answer.put("documentId", received.document().getId().toString());
        return ResponseEntity.ok(answer);
    }

    @PostMapping("/heartbeat")
    @Operation(summary = "Says the agent is alive; carries its version and operating system for the device list")
    public Map<String, Object> heartbeat(
            @RequestHeader(value = "X-Device-Key", required = false) String deviceKey,
            @RequestParam(value = "version", required = false) String version,
            @RequestParam(value = "os", required = false) String os) {
        Caller caller = caller(deviceKey);
        devices.heartbeat(caller.tenantId(), caller.deviceId(), version, os);
        return Map.of("deviceId", caller.deviceId().toString(), "status", "OK");
    }
}

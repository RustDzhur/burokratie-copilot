package de.buerokratiecopilot.devices.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.devices.DeviceService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Devices: scanners, bridges and folder watchers (spec §8, §20).
 *
 * <p>{@code DeviceService} was implemented and tested with no endpoint, so no device could be registered
 * and the Desktop Bridge had nothing to register against. This is the way in.</p>
 *
 * <p><strong>A device key is a credential and is shown once.</strong> It authenticates the device on every
 * later call, so a platform that could show it again would be a platform that stores it in a form somebody
 * can read out.</p>
 *
 * <p><strong>Revoking a device is immediate and keeps the record.</strong> A scanner that has been taken out
 * of service must stop receiving work at once; what it did before that is not erased, because a document
 * that came through it is still a document.</p>
 *
 * <p>The permissions named here are the ones the catalogue holds — {@code devices.view},
 * {@code devices.register}, {@code devices.manage} — checked before being written, after the lesson that a
 * permission nobody holds makes every endpoint answer 403 while a service test sees nothing wrong.</p>
 */
@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "Devices", description = "Scanners, bridges and folder watchers")
public class DeviceController {

    private static final String SEE = "hasAnyAuthority('devices.view','devices.manage')";
    private static final String REGISTER = "hasAnyAuthority('devices.register','devices.manage')";
    private static final String MANAGE = "hasAuthority('devices.manage')";

    private final DeviceService devices;

    public DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    /** What a caller sends to register a device. */
    public record RegisterBody(String identifier, String name, String deviceType, String connectionType) {
    }

    /** A reason, for a revocation. */
    public record ReasonBody(String reason) {
    }

    /** The devices of the account. */
    @GetMapping
    @PreAuthorize(SEE)
    @Operation(summary = "The devices of this account")
    public List<Map<String, Object>> list() {
        return devices.list(tenant());
    }

    /**
     * Registers a device.
     *
     * <p>The key comes back exactly once. It is what the device presents on every later call, and a platform
     * that could show it again would be one that keeps it in a readable form.</p>
     */
    @PostMapping
    @PreAuthorize(REGISTER)
    @Operation(summary = "Registers a device and returns its key once")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body.identifier() == null || body.identifier().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ein Gerät braucht eine Kennung, sonst lässt es sich nicht wiederfinden");
        }
        DeviceService.Registered registered = devices.register(tenantId, body.identifier(), body.name(),
                body.deviceType(), body.connectionType(), actor);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", registered.deviceId().toString());
        result.put("identifier", registered.identifier());
        result.put("status", registered.status());
        result.put("apiKey", registered.apiKey());
        result.put("note", "Dieser Schlüssel wird nur einmal angezeigt; gespeichert wird nur sein Hash.");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** One device. */
    @GetMapping("/{deviceId}")
    @PreAuthorize(SEE)
    @Operation(summary = "A device")
    public Map<String, Object> describe(@PathVariable UUID deviceId) {
        return devices.describe(tenant(), deviceId);
    }

    /** Confirms a device, which is what lets it receive work. */
    @PostMapping("/{deviceId}/activate")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Confirms a device so that it may receive work")
    public ResponseEntity<Void> activate(@PathVariable UUID deviceId) {
        devices.activate(tenant(), deviceId, CurrentUser.required());
        return ResponseEntity.noContent().build();
    }

    /**
     * Takes a device out of service.
     *
     * <p>Immediate: a device that has been withdrawn must stop receiving work at once. Nothing is deleted —
     * a document that came through it is still a document.</p>
     */
    @PostMapping("/{deviceId}/revoke")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Takes a device out of service; documents that came through it stay")
    public ResponseEntity<Void> revoke(@PathVariable UUID deviceId,
                                       @RequestBody(required = false) ReasonBody body) {
        devices.revoke(tenant(), deviceId, CurrentUser.required(),
                body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    /** When the device was last seen, which is how a broken bridge is noticed. */
    @GetMapping("/{deviceId}/last-seen")
    @PreAuthorize(SEE)
    @Operation(summary = "When this device was last seen")
    public Map<String, Object> lastSeen(@PathVariable UUID deviceId) {
        UUID tenantId = tenant();
        Instant seen = devices.lastSeen(tenantId, deviceId);
        // A device that has never been seen is not the same as one that was seen long ago, and the answer
        // says which rather than sending a zero.
        return seen == null
                ? Map.of("deviceId", deviceId.toString(), "seen", false)
                : Map.of("deviceId", deviceId.toString(), "seen", true, "lastSeenAt", seen.toString());
    }

    /** Whether a device may receive work right now, so an interface can say so before queueing anything. */
    @GetMapping("/{deviceId}/may-receive-work")
    @PreAuthorize(SEE)
    @Operation(summary = "Whether this device may receive work now")
    public Map<String, Object> mayReceiveWork(@PathVariable UUID deviceId) {
        UUID tenantId = tenant();
        return Map.of("deviceId", deviceId.toString(),
                "mayReceiveWork", devices.mayReceiveWork(tenantId, deviceId));
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}

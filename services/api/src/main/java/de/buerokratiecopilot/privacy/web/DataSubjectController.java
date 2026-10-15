package de.buerokratiecopilot.privacy.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.privacy.DataSubjectService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** Requests of a data subject about a contact: find, copy, correct, delete within limits. */
@RestController
@RequestMapping("/api/v1/privacy/subjects")
public class DataSubjectController {

    private final DataSubjectService service;

    public DataSubjectController(DataSubjectService service) {
        this.service = service;
    }

    public record Rectification(Map<String, String> fields, String reason) {
    }

    public record Erasure(String reason) {
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public List<Map<String, Object>> search(@RequestParam String q) {
        return service.search(tenant(), q);
    }

    @GetMapping("/{contactId}")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public Map<String, Object> overview(@PathVariable UUID contactId) {
        return service.overview(tenant(), CurrentUser.required(), contactId);
    }

    @GetMapping("/{contactId}/export")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public ResponseEntity<Map<String, Object>> export(@PathVariable UUID contactId) {
        Map<String, Object> bundle = service.export(tenant(), CurrentUser.required(), contactId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("auskunft-" + contactId + ".json").build().toString())
                .contentType(MediaType.APPLICATION_JSON).body(bundle);
    }

    @PutMapping("/{contactId}/rectification")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public Map<String, Object> rectify(@PathVariable UUID contactId, @RequestBody Rectification request) {
        return service.rectify(tenant(), CurrentUser.required(), contactId, request.fields(), request.reason());
    }

    @PostMapping("/{contactId}/erasure")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public Map<String, Object> erase(@PathVariable UUID contactId, @RequestBody Erasure request) {
        return service.erase(tenant(), CurrentUser.required(), contactId, request.reason());
    }

    @GetMapping("/{contactId}/requests")
    @PreAuthorize("hasAuthority('privacy.manage')")
    public List<Map<String, Object>> requests(@PathVariable UUID contactId) {
        return service.requests(tenant(), contactId);
    }
}

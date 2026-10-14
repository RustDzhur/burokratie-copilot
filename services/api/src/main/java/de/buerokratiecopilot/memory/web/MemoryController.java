package de.buerokratiecopilot.memory.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.memory.RelationshipService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** Business Memory: the relationships between documents and what they belong to. */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final RelationshipService relationships;

    public MemoryController(RelationshipService relationships) {
        this.relationships = relationships;
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }

    @GetMapping("/contacts/{id}/relationships")
    @PreAuthorize("hasAuthority('memory.view')")
    public List<Map<String, Object>> ofContact(@PathVariable UUID id, @RequestParam(required = false) String status) {
        return relationships.list(tenant(), RelationshipService.Subject.CONTACT, id, status);
    }

    @GetMapping("/contracts/{id}/relationships")
    @PreAuthorize("hasAuthority('memory.view')")
    public List<Map<String, Object>> ofContract(@PathVariable UUID id, @RequestParam(required = false) String status) {
        return relationships.list(tenant(), RelationshipService.Subject.CONTRACT, id, status);
    }

    @GetMapping("/cases/{id}/relationships")
    @PreAuthorize("hasAuthority('memory.view')")
    public List<Map<String, Object>> ofCase(@PathVariable UUID id, @RequestParam(required = false) String status) {
        return relationships.list(tenant(), RelationshipService.Subject.CASE, id, status);
    }

    @GetMapping("/documents/{id}/relationships")
    @PreAuthorize("hasAuthority('memory.view')")
    public List<Map<String, Object>> ofDocument(@PathVariable UUID id, @RequestParam(required = false) String status) {
        return relationships.list(tenant(), RelationshipService.Subject.DOCUMENT, id, status);
    }

    @PostMapping("/relationships/{id}/confirm")
    @PreAuthorize("hasAuthority('memory.confirm')")
    public Map<String, Object> confirm(@PathVariable UUID id) {
        return relationships.confirm(tenant(), id, CurrentUser.required());
    }

    @PostMapping("/relationships/{id}/reject")
    @PreAuthorize("hasAuthority('memory.reject')")
    public Map<String, Object> reject(@PathVariable UUID id) {
        return relationships.reject(tenant(), id, CurrentUser.required());
    }
}

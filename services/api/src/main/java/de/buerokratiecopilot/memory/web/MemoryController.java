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

import de.buerokratiecopilot.memory.FactService;
import de.buerokratiecopilot.memory.MemoryAskService;
import de.buerokratiecopilot.memory.MemoryRetrievalService;
import de.buerokratiecopilot.memory.RelationshipService;
import de.buerokratiecopilot.memory.TimelineService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** Business Memory: the relationships between documents and what they belong to. */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final RelationshipService relationships;
    private final FactService facts;
    private final TimelineService timeline;
    private final MemoryRetrievalService search;
    private final MemoryAskService asking;

    public MemoryController(RelationshipService relationships, FactService facts, TimelineService timeline,
                            MemoryRetrievalService search, MemoryAskService asking) {
        this.search = search;
        this.asking = asking;
        this.relationships = relationships;
        this.facts = facts;
        this.timeline = timeline;
    }

    public record SubjectRequest(String type, UUID id) {
    }

    public record AskRequest(String question) {
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

    @GetMapping("/documents/{id}/facts")
    @PreAuthorize("hasAuthority('memory.view')")
    public List<Map<String, Object>> factsOfDocument(@PathVariable UUID id) {
        return facts.factsOfDocument(tenant(), id);
    }

    @GetMapping("/facts/{id}/revisions")
    @PreAuthorize("hasAuthority('memory.view')")
    public List<Map<String, Object>> revisions(@PathVariable UUID id) {
        return facts.revisions(tenant(), id);
    }

    @GetMapping("/entities/{type}/{id}/facts")
    @PreAuthorize("hasAuthority('memory.view')")
    public List<Map<String, Object>> factsOfSubject(@PathVariable String type, @PathVariable UUID id) {
        return facts.factsOfSubject(tenant(), type.toUpperCase(java.util.Locale.ROOT), id);
    }

    @PostMapping("/facts/{id}/subject")
    @PreAuthorize("hasAuthority('memory.confirm')")
    public Map<String, Object> assignSubject(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestBody SubjectRequest request) {
        return facts.assignSubject(tenant(), id, request.type() == null ? null : request.type().toUpperCase(java.util.Locale.ROOT), request.id());
    }

    @GetMapping("/entities/{contactId}/timeline")
    @PreAuthorize("hasAuthority('memory.view')")
    public List<Map<String, Object>> contactTimeline(@PathVariable UUID contactId,
            @RequestParam(required = false) java.time.Instant before, @RequestParam(defaultValue = "50") int limit) {
        return timeline.timeline(tenant(), CurrentUser.required(), TimelineService.Subject.CONTACT, contactId, before, limit);
    }

    @GetMapping("/contracts/{id}/timeline")
    @PreAuthorize("hasAuthority('memory.view')")
    public List<Map<String, Object>> contractTimeline(@PathVariable UUID id,
            @RequestParam(required = false) java.time.Instant before, @RequestParam(defaultValue = "50") int limit) {
        return timeline.timeline(tenant(), CurrentUser.required(), TimelineService.Subject.CONTRACT, id, before, limit);
    }

    @GetMapping("/cases/{id}/timeline")
    @PreAuthorize("hasAuthority('memory.view')")
    public List<Map<String, Object>> caseTimeline(@PathVariable UUID id,
            @RequestParam(required = false) java.time.Instant before, @RequestParam(defaultValue = "50") int limit) {
        return timeline.timeline(tenant(), CurrentUser.required(), TimelineService.Subject.CASE, id, before, limit);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('memory.view')")
    public Map<String, Object> search(@RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
        MemoryRetrievalService.Result result = search.search(tenant(), CurrentUser.required(), q, limit);
        java.util.List<Map<String, Object>> hits = new java.util.ArrayList<>();
        int n = 1;
        for (MemoryRetrievalService.Hit hit : result.hits()) {
            hits.add(hit.asMap(n++));
        }
        return Map.of("query", q, "found", hits.size(), "mode", result.mode(), "hits", hits);
    }

    @PostMapping("/ask")
    @PreAuthorize("hasAuthority('memory.view')")
    public Map<String, Object> ask(@org.springframework.web.bind.annotation.RequestBody AskRequest request) {
        return asking.ask(tenant(), CurrentUser.required(), request == null ? null : request.question());
    }
}

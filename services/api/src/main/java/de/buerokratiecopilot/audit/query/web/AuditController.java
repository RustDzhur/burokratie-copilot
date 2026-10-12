package de.buerokratiecopilot.audit.query.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.audit.query.AuditQueryService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The audit log of an account (spec §30).
 *
 * <p><strong>Until this existed the platform recorded everything and told nobody.</strong> The events were
 * written — by authentication, by the document pipeline, by retention, by taxonomy administration — and
 * nothing could read them: the audit service has no read method, and the one place that queried
 * {@code audit_events} did so for its own purposes. An audit log that cannot be read answers none of the
 * questions it exists for.</p>
 *
 * <p><strong>An unknown filter is refused rather than ignored.</strong> A category that does not exist,
 * silently dropped, yields a result that looks like "nothing happened there" — and a filter that lies is
 * worse than no filter, because the person believes they narrowed the question. This is the same distinction
 * the rest of the platform keeps between "nothing found" and "nothing looked for".</p>
 *
 * <p><strong>The permission is {@code audit.view}.</strong> Being able to read who opened which document is
 * itself sensitive: the log names people and their addresses, so reading it is a distinct right from
 * administering the account.</p>
 *
 * <p><strong>Entries are read as they were recorded.</strong> The actor's label is the one stored with the
 * entry, not the person's name today, because an audit entry has to say who acted then — a log that showed
 * current names would quietly rewrite its own history when somebody changes theirs.</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit log", description = "Who did what, recorded and readable")
public class AuditController {

    private static final String SEE = "hasAnyAuthority('audit.view','audit.export')";

    private final AuditQueryService audit;

    public AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    /**
     * Events for this account, newest first.
     *
     * <p>Every filter is checked before it is applied, so a value the log cannot contain is a mistake by the
     * caller rather than an empty answer.</p>
     */
    @GetMapping("/events")
    @PreAuthorize(SEE)
    @Operation(summary = "Audit events, newest first; an unknown filter is refused rather than ignored")
    public Map<String, Object> events(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Integer limit) {
        UUID tenantId = tenant();

        List<Map<String, Object>> events = audit.events(tenantId, from, to, category, result, entityType,
                limit);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("events", events);
        answer.put("count", events.size());
        // Said in words: an empty list here means this account has no matching entries, and the filters that
        // were applied are repeated so that a person can see what was actually asked.
        answer.put("filters", Map.of(
                "from", from == null ? "unbegrenzt" : from.toString(),
                "to", to == null ? "unbegrenzt" : to.toString(),
                "category", category == null ? "alle" : category,
                "result", result == null ? "alle" : result,
                "entityType", entityType == null ? "alle" : entityType));
        answer.put("note", events.isEmpty()
                ? "Für diese Auswahl liegen keine Einträge vor."
                : "Die Einträge sind so wiedergegeben, wie sie aufgezeichnet wurden.");
        return answer;
    }

    /** The categories an entry can have, so an interface does not hard-code them. */
    @GetMapping("/categories")
    @PreAuthorize(SEE)
    @Operation(summary = "The categories an audit entry can have")
    public List<String> categories() {
        return java.util.Arrays.stream(AuditEvent.Category.values()).map(Enum::name).sorted().toList();
    }

    /** The results an entry can have — succeeded, failed, refused. */
    @GetMapping("/results")
    @PreAuthorize(SEE)
    @Operation(summary = "The results an audit entry can have")
    public List<String> results() {
        return java.util.Arrays.stream(AuditEvent.Result.values()).map(Enum::name).sorted().toList();
    }

    /** The tenant the caller is acting in, which is never taken from a parameter. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}

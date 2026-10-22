package de.buerokratiecopilot.documents.web;

import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.BelegStatus;
import de.buerokratiecopilot.shared.context.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The counters above the Posteingang (Kanzlei edition, §5.1).
 *
 * <p>Six numbers, and the office reads them in one glance: what is new, what is accepted, what needs a person,
 * what has left, what failed - and what has no client yet, which is the one that must never disappear into a
 * silent pile.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Belege")
class BelegQueueController {

    private final JdbcTemplate jdbc;
    private final DocumentService documents;

    BelegQueueController(JdbcTemplate jdbc, DocumentService documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    /** The client a voucher belongs to; null removes an assignment made by mistake. */
    record MandantRequest(java.util.UUID mandantId) {
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}/mandant")
    @PreAuthorize("hasAuthority('documents.edit')")
    @Operation(summary = "Assign a voucher to a client, or take it away")
    java.util.Map<String, Object> assignMandant(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
            @org.springframework.web.bind.annotation.RequestBody MandantRequest request) {
        var saved = documents.assignMandant(id, request.mandantId());
        return java.util.Map.of(
                "id", saved.getId().toString(),
                "mandantId", saved.getMandantId() == null ? "" : saved.getMandantId().toString());
    }

    /** What the office asks when it moves a voucher: accept it, send it to a person, or finish it. */
    record BelegStatusRequest(BelegStatus status) {
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}/beleg-status")
    @PreAuthorize("hasAuthority('documents.edit')")
    @Operation(summary = "Move a voucher through the office queue")
    java.util.Map<String, Object> changeStatus(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
            @org.springframework.web.bind.annotation.RequestBody BelegStatusRequest request) {
        var saved = documents.changeBelegStatus(id, request.status());
        return java.util.Map.of("id", saved.getId().toString(), "belegStatus", saved.getBelegStatus().name());
    }

    @GetMapping("/beleg-counts")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "How many vouchers stand where in the queue")
    Map<String, Object> counts() {
        var tenant = RequestContext.tenantId().orElseThrow();

        // One query, then fill every counter: six round trips for six numbers would be six chances to show an
        // inconsistent screen.
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (BelegStatus status : BelegStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        jdbc.query("""
                select beleg_status, count(*) as anzahl
                  from documents
                 where tenant_id = ? and deleted_at is null
                 group by beleg_status
                """, rs -> {
                    byStatus.put(rs.getString("beleg_status"), rs.getLong("anzahl"));
                }, tenant);

        long ohneMandant = jdbc.queryForObject("""
                select count(*) from documents
                 where tenant_id = ? and deleted_at is null and mandant_id is null
                """, Long.class, tenant);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("neu", byStatus.get(BelegStatus.NEU.name()));
        answer.put("offen", byStatus.get(BelegStatus.OFFEN.name()));
        answer.put("zuPruefen", byStatus.get(BelegStatus.ZU_PRUEFEN.name()));
        answer.put("exportiert", byStatus.get(BelegStatus.EXPORTIERT.name()));
        answer.put("fehler", byStatus.get(BelegStatus.FEHLER.name()));
        // Null, not zero, until §5.2 adds the client link: a zero would claim "every voucher has a client",
        // and the screen must be able to say "not known yet" instead of inventing an answer.
        answer.put("ohneMandant", ohneMandant);
        return answer;
    }
}

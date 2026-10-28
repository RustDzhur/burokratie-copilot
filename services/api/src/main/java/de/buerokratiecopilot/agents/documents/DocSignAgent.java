package de.buerokratiecopilot.agents.documents;

import java.time.Instant;

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
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code doc-sign} — Signatur-Prozess.
 *
 * <p>Prepares documents for signature without external providers: for every document that may be
 * signed, a task is created for the signer with a due date, a completion criterion and the
 * {@code signature} tag. Idempotent: a document whose signature task is still open is not prepared
 * a second time; duplicates and cancelled documents are skipped with a reason.</p>
 */
@Component
public class DocSignAgent implements Agent {

    static final int DEFAULT_DUE_DAYS = 5;
    static final String TAG = "signature";
    static final String TITLE_PREFIX = "Dokument zur Unterschrift";

    private final DocumentRepository documents;
    private final TaskService tasks;
    private final JdbcTemplate jdbc;

    public DocSignAgent(DocumentRepository documents, TaskService tasks, JdbcTemplate jdbc) {
        this.documents = documents;
        this.tasks = tasks;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "doc-sign";
    }

    @Override
    public AgentResult run(AgentContext context) {
        return RequestContext.runAs(context.tenantId(), () -> prepare(context));
    }

    private AgentResult prepare(AgentContext context) {
        List<UUID> documentIds = parseDocumentIds(context);
        if (documentIds.isEmpty()) {
            return AgentResult.skipped(
                    "Keine documentIds angegeben; es gibt keine Dokumente zur Unterschrift vorzubereiten.");
        }

        UUID signerUserId = parseUuid(context.input("signerUserId"));
        int dueDays = parseInt(context.input("dueDays"), DEFAULT_DUE_DAYS);
        Instant dueAt = context.now().plus(java.time.Duration.ofDays(Math.max(dueDays, 1)));

        if (signerUserId != null) {
            Integer membership = jdbc.queryForObject("""
                    select count(*) from memberships
                    where user_id = ? and tenant_id = ? and status = 'ACTIVE' and revoked_at is null
                    """, Integer.class, signerUserId, context.tenantId());
            if (membership == null || membership == 0) {
                return AgentResult.failed("Der Unterzeichner " + signerUserId
                        + " ist kein aktives Mitglied dieses Mandanten.");
            }
        }

        List<AgentResult.Produced> produced = new ArrayList<>();
        int prepared = 0;
        int skipped = 0;
        int missing = 0;
        List<String> skipReasons = new ArrayList<>();

        for (UUID documentId : documentIds) {
            Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId,
                    context.tenantId()).orElse(null);
            if (document == null) {
                missing++;
                continue;
            }
            if (document.getProcessingStatus() == Document.ProcessingStatus.DUPLICATE
                    || document.getProcessingStatus() == Document.ProcessingStatus.CANCELLED) {
                skipped++;
                skipReasons.add(document.getOriginalFilename() + ": Status "
                        + document.getProcessingStatus());
                continue;
            }
            Integer openTask = jdbc.queryForObject("""
                    select count(*) from tasks
                    where source_document_id = ? and source = 'DOCUMENT' and deleted_at is null
                      and status not in ('DONE', 'CANCELLED')
                      and title like ?
                    """, Integer.class, documentId, TITLE_PREFIX + "%");
            if (openTask != null && openTask > 0) {
                skipped++;
                skipReasons.add(document.getOriginalFilename() + ": bereits vorbereitet");
                continue;
            }

            Task task = tasks.create(new TaskService.CreateRequest(
                    context.tenantId(), null, null,
                    TITLE_PREFIX + ": " + document.getOriginalFilename(),
                    "Das Dokument " + document.getOriginalFilename()
                            + " liegt zur Unterschrift bereit. Bitte unterzeichnen oder die "
                            + "Unterschrift ablehnen.",
                    Task.Priority.NORMAL, "RULE",
                    "Vom Agenten doc-sign aus dem Dokument " + document.getId() + " abgeleitet",
                    dueAt, null,
                    "Das Dokument wurde unterzeichnet oder die Unterschrift wurde abgelehnt.",
                    false, null, signerUserId, List.of(TAG), Task.Source.DOCUMENT, documentId),
                    context.actorUserId());
            prepared++;
            produced.add(new AgentResult.Produced("signature-task", task.getId().toString(),
                    document.getOriginalFilename()));
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("prepared", String.valueOf(prepared));
        meta.put("skipped", String.valueOf(skipped));
        meta.put("missing", String.valueOf(missing));
        meta.put("dueAt", dueAt.toString());

        if (prepared == 0) {
            return AgentResult.skipped("Keine Signatur-Aufgabe angelegt: "
                    + (skipped > 0 ? skipped + " Dokument(e) übersprungen (bereits vorbereitet "
                            + "oder nicht unterzeichnungsfähig: " + String.join(", ", skipReasons) + ")."
                            : missing + " Dokument(e) nicht gefunden."));
        }
        return AgentResult.done(prepared + " Signatur-Aufgabe(n) angelegt"
                        + (skipped > 0 ? ", " + skipped + " übersprungen" : ""),
                produced, meta);
    }

    private static List<UUID> parseDocumentIds(AgentContext context) {
        List<UUID> ids = new ArrayList<>();
        String list = context.input("documentIds");
        if (isBlank(list)) {
            list = context.input("documentId");
        }
        if (isBlank(list)) {
            return ids;
        }
        for (String part : list.split(",")) {
            UUID id = parseUuid(part);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

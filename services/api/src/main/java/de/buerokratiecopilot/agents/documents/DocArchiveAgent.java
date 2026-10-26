package de.buerokratiecopilot.agents.documents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentReviewRepository;
import de.buerokratiecopilot.documents.DocumentTypeFieldRepository;
import de.buerokratiecopilot.documents.DocumentTypeRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.documents.domain.DocumentReview;
import de.buerokratiecopilot.documents.domain.DocumentStatusMachine;
import de.buerokratiecopilot.documents.domain.DocumentTypeField;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * {@code doc-archive} — Dokument-Archivierung.
 *
 * <p>Confirms that a document is ready for archival storage and stamps the date. Readiness means:
 * no legal hold, no open review, no missing required fields, and a processing status that may move
 * to {@code ARCHIVED} in the status machine. Everything else is refused with the exact reason —
 * archiving a document that failed its checks would make the archive answer for decisions that
 * were never checked. Idempotent: an archived document is reported, not archived twice.</p>
 */
@Component
public class DocArchiveAgent implements Agent {

    private static final String GENERIC_TYPE = "unbekannt";

    private final DocumentRepository documents;
    private final DocumentExtractionRepository extractions;
    private final DocumentTypeFieldRepository fieldDefinitions;
    private final DocumentTypeRepository types;
    private final DocumentReviewRepository reviews;

    public DocArchiveAgent(DocumentRepository documents, DocumentExtractionRepository extractions,
                           DocumentTypeFieldRepository fieldDefinitions, DocumentTypeRepository types,
                           DocumentReviewRepository reviews) {
        this.documents = documents;
        this.extractions = extractions;
        this.fieldDefinitions = fieldDefinitions;
        this.types = types;
        this.reviews = reviews;
    }

    @Override
    public String id() {
        return "doc-archive";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID documentId = parseUuid(context.input("documentId"));
        if (documentId == null) {
            return AgentResult.skipped("Keine documentId angegeben; es gibt nichts zu archivieren.");
        }
        return RequestContext.runAs(context.tenantId(), () -> archive(context, documentId));
    }

    private AgentResult archive(AgentContext context, UUID documentId) {
        Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, context.tenantId())
                .orElse(null);
        if (document == null) {
            return AgentResult.failed(
                    "Dokument " + documentId + " wurde nicht gefunden (oder gehört einem anderen Tenant).");
        }
        if (document.getProcessingStatus() == Document.ProcessingStatus.ARCHIVED) {
            return AgentResult.skipped("Das Dokument ist bereits archiviert"
                    + (document.getArchivedAt() == null ? "" : " (am " + document.getArchivedAt() + ")"));
        }
        if (document.isLegalHold()) {
            return AgentResult.failed(
                    "Das Dokument steht unter Legal Hold und darf nicht archiviert werden.");
        }

        List<String> blockers = new ArrayList<>();

        if (document.getReviewStatus() == Document.ReviewStatus.PENDING
                || document.getReviewStatus() == Document.ReviewStatus.IN_REVIEW) {
            blockers.add("der Prüfstatus ist " + document.getReviewStatus());
        }
        reviews.findFirstByDocumentIdAndStatusIn(document.getId(),
                List.of(DocumentReview.Status.PENDING, DocumentReview.Status.IN_REVIEW))
                .ifPresent(review -> blockers.add("es steht noch eine Prüfung aus (Review "
                        + review.getId() + ")"));
        List<String> missing = missingRequiredFields(document);
        if (!missing.isEmpty()) {
            blockers.add("Pflichtfeld(er) ohne Wert: " + String.join(", ", missing));
        }
        try {
            DocumentStatusMachine.requireTransition(document.getProcessingStatus(),
                    Document.ProcessingStatus.ARCHIVED);
        }
        catch (de.buerokratiecopilot.documents.domain.InvalidDocumentStatusTransition ex) {
            blockers.add("der Status " + document.getProcessingStatus()
                    + " erlaubt keine Archivierung");
        }
        if (!blockers.isEmpty()) {
            return AgentResult.failed("Das Dokument ist nicht archivreif: "
                    + String.join("; ", blockers));
        }

        Document.ProcessingStatus previous = document.getProcessingStatus();
        document.setProcessingStatus(Document.ProcessingStatus.ARCHIVED);
        if (document.getArchivedAt() == null) {
            document.setArchivedAt(Instant.now());
        }
        documents.save(document);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("documentId", document.getId().toString());
        meta.put("archivedAt", String.valueOf(document.getArchivedAt()));
        meta.put("previousStatus", previous.name());
        return AgentResult.done(
                "Dokument archiviert am " + document.getArchivedAt() + ".",
                List.of(new AgentResult.Produced("document-archived", document.getId().toString(),
                        document.getOriginalFilename())),
                meta);
    }

    private List<String> missingRequiredFields(Document document) {
        List<DocumentTypeField> fields = fieldsOf(document);
        Map<String, DocumentExtraction> byKey = new LinkedHashMap<>();
        for (DocumentExtraction extraction : extractions.findByDocumentId(document.getId())) {
            byKey.put(extraction.getFieldKey(), extraction);
        }
        List<String> missing = new ArrayList<>();
        for (DocumentTypeField field : fields) {
            if (!field.isRequired()) {
                continue;
            }
            DocumentExtraction value = byKey.get(field.getFieldKey());
            if (value == null || value.getFieldValue() == null || value.getFieldValue().isBlank()) {
                missing.add(field.getFieldKey());
            }
        }
        return missing;
    }

    private List<DocumentTypeField> fieldsOf(Document document) {
        if (document.getDocumentTypeId() != null) {
            List<DocumentTypeField> fields = fieldDefinitions
                    .findByDocumentTypeIdOrderBySortOrderAsc(document.getDocumentTypeId());
            if (!fields.isEmpty()) {
                return fields;
            }
        }
        return types.findByCode(GENERIC_TYPE)
                .map(type -> fieldDefinitions.findByDocumentTypeIdOrderBySortOrderAsc(type.getId()))
                .orElse(List.of());
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

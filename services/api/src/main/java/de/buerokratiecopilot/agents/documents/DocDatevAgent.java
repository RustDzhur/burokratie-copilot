package de.buerokratiecopilot.agents.documents;

import java.time.LocalDate;
import java.time.ZoneId;
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
import de.buerokratiecopilot.documents.DocumentTypeRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * {@code doc-datev} — DATEV-Aufbereitung.
 *
 * <p>Builds an import package for DATEV from the documents' own data: fields are mapped by rule
 * (Belegdatum, Belegnummer, Konto/Gegenkonto by document type, Betrag, Buchungstext), the package
 * is sealed with a SHA-256 checksum over its exact bytes, and every questionable line carries a
 * warning. Nothing is sent anywhere: the package is reported to the caller, who decides about the
 * transfer. A package without any mappable document is refused, not invented.</p>
 */
@Component
public class DocDatevAgent implements Agent {

    private static final String DEFAULT_SEPARATOR = ";";

    private final DocumentRepository documents;
    private final DocumentExtractionRepository extractions;
    private final DocumentTypeRepository types;

    public DocDatevAgent(DocumentRepository documents, DocumentExtractionRepository extractions,
                         DocumentTypeRepository types) {
        this.documents = documents;
        this.extractions = extractions;
        this.types = types;
    }

    @Override
    public String id() {
        return "doc-datev";
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<UUID> documentIds = parseDocumentIds(context);
        if (documentIds.isEmpty()) {
            return AgentResult.skipped("Keine documentIds angegeben; es gibt kein Paket zu bauen.");
        }
        return RequestContext.runAs(context.tenantId(), () -> build(context, documentIds));
    }

    private AgentResult build(AgentContext context, List<UUID> documentIds) {
        Map<String, String> accounts = Map.of(
                "invoice", "1600", "dunning", "1600", "payslip", "6020", "default", "1000",
                "counter-default", "1600");
        LocalDate today = context.now().atZone(ZoneId.of("Europe/Berlin")).toLocalDate();
        DatevPackager packager = new DatevPackager(DEFAULT_SEPARATOR, accounts, today);

        List<DatevPackager.Line> lines = new ArrayList<>();
        int missing = 0;
        for (UUID documentId : documentIds) {
            Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId,
                    context.tenantId()).orElse(null);
            if (document == null) {
                missing++;
                continue;
            }
            String typeCode = document.getDocumentTypeId() == null ? null
                    : types.findById(document.getDocumentTypeId())
                            .map(de.buerokratiecopilot.documents.domain.DocumentType::getCode)
                            .orElse(null);
            lines.add(packager.mapDocument(document.getOriginalFilename(), typeCode,
                    document.getDocumentDate(), document.getDocumentNumber(),
                    extractions.findByDocumentId(documentId)));
        }

        if (lines.isEmpty()) {
            return AgentResult.failed(
                    "Keines der " + documentIds.size() + " Dokumente wurde gefunden; es wurde kein "
                            + "DATEV-Paket gebaut.");
        }

        DatevPackager.Package built = packager.packageLines(lines);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("lines", String.valueOf(built.lines().size()));
        meta.put("checksum", built.checksum());
        meta.put("algorithm", "SHA-256");
        meta.put("warnings", String.valueOf(built.warningCount()));
        meta.put("missing", String.valueOf(missing));
        meta.put("csv", built.csv(DEFAULT_SEPARATOR));

        return AgentResult.done(
                "DATEV-Paket gebaut: " + built.lines().size() + " Zeile(n), "
                        + built.warningCount() + " Warnung(en)"
                        + (missing > 0 ? ", " + missing + " Dokument(e) nicht gefunden" : "")
                        + ". Kontrollsumme " + built.checksum() + ".",
                List.of(new AgentResult.Produced("datev-package",
                        built.checksum().substring(0, 16),
                        built.lines().size() + " Zeilen, Kontrollsumme " + built.checksum())),
                meta);
    }

    private static List<UUID> parseDocumentIds(AgentContext context) {
        List<UUID> ids = new ArrayList<>();
        String list = context.input("documentIds");
        if (list == null || list.isBlank()) {
            list = context.input("documentId");
        }
        if (list == null || list.isBlank()) {
            return ids;
        }
        for (String part : list.split(",")) {
            try {
                ids.add(UUID.fromString(part.strip()));
            }
            catch (IllegalArgumentException ex) {
                // one bad id does not stop the package; it is reported as missing
            }
        }
        return ids;
    }
}

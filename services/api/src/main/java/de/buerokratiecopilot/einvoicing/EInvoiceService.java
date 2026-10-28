package de.buerokratiecopilot.einvoicing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.einvoicing.EInvoiceParser.NotAnEInvoiceException;
import de.buerokratiecopilot.einvoicing.EInvoiceParser.ParsedInvoice;
import de.buerokratiecopilot.einvoicing.domain.EInvoiceRecord;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Stores an electronic invoice and what the platform read from it (spec §14, D-005).
 *
 * <p>Three things happen here, and the order matters.</p>
 *
 * <p><strong>The original XML is stored first and unchanged.</strong> Whatever the platform concludes
 * about the file, the file itself is kept byte for byte in object storage. An invoice that was read
 * wrongly must be re-readable once the reader is fixed, and that is only possible if the source was
 * never rewritten.</p>
 *
 * <p><strong>A PDF is only recorded as an electronic invoice when it carried XML.</strong> A PDF
 * without embedded XML produces no record at all — not one marked "unsupported". It is a document, and
 * the document module already has it; creating an e-invoice record for it would put a file into a list
 * where a user expects machine-readable invoices.</p>
 *
 * <p><strong>An unreadable file is recorded as unsupported rather than dropped.</strong> A supplier who
 * sends a syntax the platform cannot read is a fact the operator needs to know, and a record saying so
 * is how they find out.</p>
 */
@Service
public class EInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceService.class);

    /** The content type the XML is stored under; it is always XML, whatever the file was called. */
    private static final String XML_CONTENT_TYPE = "application/xml";

    private final EInvoiceRecordRepository records;
    private final ZugferdExtractor zugferd;
    private final BlobStore blobStore;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public EInvoiceService(EInvoiceRecordRepository records, ZugferdExtractor zugferd,
                           BlobStore blobStore, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.records = records;
        this.zugferd = zugferd;
        this.blobStore = blobStore;
        this.jdbc = jdbc;
    }

    /** What importing a file produced. */
    public record Imported(EInvoiceRecord record, ParsedInvoice parsed, String reason) {

        public boolean accepted() {
            return record != null;
        }

        public Map<String, Object> asDiagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("accepted", accepted());
            details.put("reason", reason);
            if (record != null) {
                details.put("recordId", record.getId().toString());
                details.put("format", record.getFormat().name());
                details.put("validationStatus", record.getValidationStatus().name());
            }
            return details;
        }
    }

    /**
     * Imports a file as an electronic invoice.
     *
     * @param content the file; for a PDF the XML is taken out of it, for an XML file it is the invoice
     * @return the record, or an explanation of why the file is not one
     */
    @Transactional
    public Imported importFile(UUID tenantId, UUID documentId, byte[] content, String declaredName,
                               UUID userId) throws IOException {
        if (content == null || content.length == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Datei ist leer");
        }

        byte[] xml;
        EInvoiceRecord.Format format;
        if (EInvoiceParser.looksLikeXml(content)) {
            xml = content;
            format = EInvoiceRecord.Format.OTHER;
        }
        else {
            Optional<ZugferdExtractor.EmbeddedXml> embedded = zugferd.extractXml(content);
            if (embedded.isEmpty()) {
                // The acceptance criterion: a PDF is not an electronic invoice. No record is created,
                // because a list of machine-readable invoices must not contain pictures of invoices.
                log.info("File {} carries no invoice XML; it is a document, not an e-invoice",
                        declaredName);
                return new Imported(null, null,
                        "Die Datei enthält kein Rechnungs-XML. Ein PDF ohne eingebettetes XML ist keine "
                                + "elektronische Rechnung, sondern ein Dokument.");
            }
            xml = embedded.get().content();
            format = EInvoiceRecord.Format.ZUGFERD;
        }

        EInvoiceParser.Syntax syntax;
        ParsedInvoice parsed;
        try {
            parsed = EInvoiceParser.parse(xml);
            syntax = parsed.syntax();
        }
        catch (NotAnEInvoiceException ex) {
            // Stored and recorded as unsupported: a supplier sending a syntax the platform cannot read
            // is something the operator has to be able to see.
            UUID blobId = storeOriginal(tenantId, xml);
            EInvoiceRecord record = new EInvoiceRecord(UUID.randomUUID(), tenantId,
                    EInvoiceRecord.Format.OTHER);
            record.setDocumentId(documentId);
            record.setSyntax(ex.reason());
            record.setValidationStatus(EInvoiceRecord.ValidationStatus.NOT_SUPPORTED);
            record.setValidationResults(Map.of("errors",
                    List.of(Map.of("code", ex.reason(), "message", ex.getMessage()))));
            record.setOriginalXmlBlobId(blobId);
            record.setExtractedAt(Instant.now());
            EInvoiceRecord saved = records.save(record);
            log.info("E-invoice {} stored as unsupported: {}", saved.getId(), ex.reason());
            return new Imported(saved, null, ex.getMessage());
        }

        if (format == EInvoiceRecord.Format.OTHER && syntax == EInvoiceParser.Syntax.CII) {
            // An XML file that is CII is a ZUGFeRD invoice even without a PDF around it.
            format = EInvoiceRecord.Format.ZUGFERD;
        }
        else if (format == EInvoiceRecord.Format.OTHER) {
            format = EInvoiceRecord.Format.XRECHNUNG;
        }

        UUID blobId = storeOriginal(tenantId, xml);

        EInvoiceRecord record = new EInvoiceRecord(UUID.randomUUID(), tenantId, format);
        record.setDocumentId(documentId);
        record.setSyntax(syntax.name());
        record.setProfile(parsed.profileName());
        record.setValidationStatus(EInvoiceRecord.ValidationStatus.VALID);
        record.setValidationResults(buildValidationResults(parsed));
        record.setOriginalXmlBlobId(blobId);
        record.setExtractedAt(Instant.now());
        record.setValidatedAt(Instant.now());
        EInvoiceRecord saved = records.save(record);

        // The caller records the invoice itself; this service owns the file and what was read from it.
        log.info("E-invoice {} imported: {} {}, {} line(s)", saved.getId(), format, syntax,
                parsed.lines().size());
        return new Imported(saved, parsed, null);
    }

    /**
     * Stores the XML unchanged.
     *
     * <p>No normalisation, no re-serialisation, no pretty printing: a file that was changed is no longer
     * the file the supplier sent, and a dispute about an amount would then be about the platform's copy
     * rather than about the invoice.</p>
     */
    private UUID storeOriginal(UUID tenantId, byte[] xml) {
        BlobStore.StoredBlob stored = blobStore.store(tenantId, new ByteArrayInputStream(xml), xml.length,
                XML_CONTENT_TYPE);

        // The record points at a document blob, so the object has to be registered as one. The row is
        // written here rather than through the document module because the XML is not a document the
        // user uploaded: it is the machine-readable half of a file the platform already has.
        return jdbc.queryForObject("""
                insert into document_blobs (id, tenant_id, storage_key, storage_backend, bucket,
                                            size_bytes, content_hash, content_type, scan_status,
                                            scan_details, quarantined)
                values (gen_random_uuid(), ?, ?, ?, null, ?, ?, ?, 'CLEAN', '{}'::jsonb, false)
                returning id
                """, UUID.class, tenantId, stored.storageKey(), stored.backend(), stored.sizeBytes(),
                stored.contentHash(), XML_CONTENT_TYPE);
    }

    /** What the platform found, in the shape the validation column expects. */
    private Map<String, Object> buildValidationResults(ParsedInvoice parsed) {
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();

        for (String missing : parsed.missingRequiredFields()) {
            errors.add(Map.of("code", "MISSING_" + missing.toUpperCase(java.util.Locale.ROOT),
                    "field", missing,
                    "message", "Das Feld " + missing + " fehlt in der Rechnung"));
        }
        if (parsed.lines().isEmpty()) {
            warnings.add(Map.of("code", "NO_LINE_ITEMS",
                    "message", "Die Rechnung enthält keine Positionen"));
        }
        if (!parsed.unrecognised().isEmpty()) {
            warnings.add(Map.of("code", "UNREAD_ELEMENTS",
                    "elements", parsed.unrecognised(),
                    "message", "Nicht gelesene Elemente: " + String.join(", ", parsed.unrecognised())));
        }

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("errors", errors);
        results.put("warnings", warnings);
        results.put("checkedAt", Instant.now().toString());
        results.put("syntax", parsed.syntax().name());
        return results;
    }

    /** The stored XML of a record, exactly as it arrived. */
    @Transactional(readOnly = true)
    public byte[] originalXml(UUID recordId, UUID tenantId) {
        EInvoiceRecord record = requireRecord(recordId, tenantId);
        if (record.getOriginalXmlBlobId() == null) {
            throw ApiException.notFound("the stored XML of this record");
        }
        String storageKey = jdbc.queryForObject(
                "select storage_key from document_blobs where id = ?", String.class,
                record.getOriginalXmlBlobId());
        try (InputStream content = blobStore.retrieve(storageKey)) {
            return content.readAllBytes();
        }
        catch (IOException ex) {
            throw new IllegalStateException(
                    "Das gespeicherte XML konnte nicht gelesen werden: " + ex.getMessage(), ex);
        }
    }

    @Transactional(readOnly = true)
    public EInvoiceRecord requireRecord(UUID recordId, UUID tenantId) {
        return records.findByIdAndTenantId(recordId, tenantId)
                .orElseThrow(() -> ApiException.notFound("e-invoice record"));
    }

    @Transactional(readOnly = true)
    public List<EInvoiceRecord> recordsOfDocument(UUID documentId, UUID tenantId) {
        return records.findByDocumentIdAndTenantId(documentId, tenantId);
    }

    /** Records whose validation ended in a state a person should look at. */
    @Transactional(readOnly = true)
    public List<EInvoiceRecord> needingAttention(UUID tenantId) {
        return records.findByTenantIdAndValidationStatusIn(tenantId,
                List.of(EInvoiceRecord.ValidationStatus.INVALID,
                        EInvoiceRecord.ValidationStatus.NOT_SUPPORTED));
    }
}

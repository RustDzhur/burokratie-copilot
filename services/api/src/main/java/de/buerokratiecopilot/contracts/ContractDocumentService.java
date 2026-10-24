package de.buerokratiecopilot.contracts;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.contracts.ContractTemplateValues.ContactData;
import de.buerokratiecopilot.contracts.ContractTemplateValues.ContractData;
import de.buerokratiecopilot.contracts.ContractTemplateValues.OrganizationData;
import de.buerokratiecopilot.contracts.ContractTemplateValues.PartyData;
import de.buerokratiecopilot.contracts.ContractTemplateValues.WorkspaceData;
import de.buerokratiecopilot.documents.content.RichDocument;
import de.buerokratiecopilot.documents.export.DocumentExporter;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.storage.BlobStore;
import de.buerokratiecopilot.templates.GeneratedDocumentExportService;
import de.buerokratiecopilot.templates.GeneratedDocumentService;
import de.buerokratiecopilot.templates.TemplateRenderer;
import de.buerokratiecopilot.templates.TemplateRenderer.Field;

/**
 * Generating the actual contract document from a CONTRACT template (spec §13, V72).
 *
 * <p>The owner's complaint was that the platform keeps a contract record and no contract: the record
 * exists, and the document it describes does not. This closes the gap in one act — render the template
 * with the contract's own data, export it as PDF and DOCX, keep both files as documents of the account
 * and bind the PDF to a new contract version, so the contract page can show and download what belongs to
 * the contract.</p>
 *
 * <p><strong>A refused render writes nothing.</strong> The renderer runs before anything is stored; when
 * a placeholder is unknown or a required field has no value the attempt ends with a 422 that names every
 * missing piece, and neither a generated record nor a version nor a file exists afterwards.</p>
 *
 * <p><strong>The stored document is a document like any other.</strong> The file goes into the blob store
 * with its metadata, its version and its hash, exactly like an upload — so it can be downloaded, searched
 * and sent again. Identical bytes are stored once (the schema forbids two live documents with the same
 * content in one account).</p>
 */
@Service
public class ContractDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ContractDocumentService.class);

    /** The note every generated version carries, so its origin stays visible in the version list. */
    public static final String CHANGE_NOTE = "Dokument aus Vorlage erzeugt";

    private final JdbcTemplate jdbc;
    private final DocumentExporter exporter;
    private final GeneratedDocumentExportService export;
    private final ContractVersionService versions;
    private final BlobStore blobs;
    private final AuditService audit;

    public ContractDocumentService(JdbcTemplate jdbc, DocumentExporter exporter,
                                   GeneratedDocumentExportService export,
                                   ContractVersionService versions, BlobStore blobs,
                                   AuditService audit) {
        this.jdbc = jdbc;
        this.exporter = exporter;
        this.export = export;
        this.versions = versions;
        this.blobs = blobs;
        this.audit = audit;
    }

    /** What a generation produced. */
    public record Generated(UUID versionId, UUID documentId, UUID pdfRecordId, UUID docxRecordId,
                            List<String> usedFields) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("versionId", versionId.toString());
            map.put("documentId", documentId.toString());
            map.put("generatedDocuments", Map.of(
                    "pdf", pdfRecordId.toString(),
                    "docx", docxRecordId.toString()));
            map.put("usedFields", usedFields);
            return map;
        }
    }

    /**
     * Generates the document of a contract from a CONTRACT template: render, export, store and bind to a
     * new current version.
     *
     * @throws ApiException 422 when the render has unknown or empty required fields; 404 for a contract or
     *                      template that does not exist; 409 for an unpublished template version
     */
    @Transactional
    public Generated generate(UUID tenantId, UUID contractId, UUID templateId, UUID actorId) {
        Map<String, Object> contractRow = requireRow("""
                select id, workspace_id, organization_id, counterparty_contact_id, contract_number, title,
                       contract_type, status, description, start_date, end_date, signed_date, auto_renewal,
                       renewal_period_months, notice_period_value, notice_period_unit, notice_deadline_date,
                       cost_amount, cost_currency, cost_interval
                  from contracts where id = ? and tenant_id = ? and deleted_at is null
                """, "contract", contractId, tenantId);

        Map<String, Object> templateRow = requireRow("""
                select id, code, name->>'de' as name, category, current_version
                  from document_templates
                 where id = ? and is_active = true and (tenant_id = ? or tenant_id is null)
                """, "template", templateId, tenantId);
        String category = String.valueOf(templateRow.get("category"));
        if (!"CONTRACT".equalsIgnoreCase(category)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Die Vorlage ist vom Typ " + category
                    + "; ein Vertragsdokument wird nur aus einer CONTRACT-Vorlage erzeugt");
        }

        int templateVersion = ((Number) templateRow.get("current_version")).intValue();
        Map<String, Object> versionRow = requireRow("""
                select id, version, body_html, status from template_versions
                 where template_id = ? and (tenant_id = ? or tenant_id is null) and version = ?
                """, "template version", templateId, tenantId, templateVersion);
        if (!"PUBLISHED".equals(String.valueOf(versionRow.get("status")))) {
            throw ApiException.conflict("Diese Vorlagenversion ist noch nicht veröffentlicht und wird "
                    + "nicht zur Erzeugung verwendet");
        }

        List<Field> fields = fieldsOf(tenantId, templateId);
        Map<String, String> values = resolveValues(contractRow, fields);
        String bodyHtml = String.valueOf(versionRow.get("body_html"));

        TemplateRenderer.Result rendered = TemplateRenderer.render(bodyHtml, fields, values);
        if (!rendered.isRenderable()) {
            // The acceptance criterion from the letter flow: a document with holes is not produced at all,
            // and the answer names everything that has to be fixed. The names travel as field violations so
            // the 422 body carries them machine-readably, not only inside the localised sentence.
            List<de.buerokratiecopilot.shared.error.ApiErrorResponse.FieldViolation> violations =
                    new ArrayList<>();
            for (String unresolved : rendered.unresolved()) {
                violations.add(new de.buerokratiecopilot.shared.error.ApiErrorResponse.FieldViolation(
                        unresolved, "UNRESOLVED", "Unbekannter Platzhalter"));
            }
            for (String empty : rendered.emptyRequired()) {
                violations.add(new de.buerokratiecopilot.shared.error.ApiErrorResponse.FieldViolation(
                        empty, "EMPTY", "Pflichtfeld ohne Wert"));
            }
            throw new ApiException(ErrorCode.VALIDATION_FAILED, rendered.problem(), violations);
        }

        String title = String.valueOf(contractRow.get("title"));
        RichDocument.Document document = new RichDocument.Document(title, htmlBlocks(rendered.text()), null,
                null, false, string(contractRow.get("contract_number")));

        Map<String, Object> fieldValues = new LinkedHashMap<>();
        values.forEach((key, value) -> fieldValues.put(key, value));

        // Both formats, because the PDF is what is filed and the DOCX is what somebody edits. Each file
        // becomes a document of the account and its generated record points at it.
        GeneratedDocumentExportService.Produced pdf = export.produce(tenantId,
                new GeneratedDocumentExportService.Request(document, templateId,
                        (UUID) versionRow.get("id"), null, "contract", contractId, "PDF", fieldValues,
                        List.of()),
                actorId);
        GeneratedDocumentExportService.Produced docx = export.produce(tenantId,
                new GeneratedDocumentExportService.Request(document, templateId,
                        (UUID) versionRow.get("id"), null, "contract", contractId, "DOCX", fieldValues,
                        List.of()),
                actorId);

        UUID pdfDocumentId = storeFile(tenantId, actorId, contractId, templateId, contractRow, pdf,
                pdf.record().id());
        storeFile(tenantId, actorId, contractId, templateId, contractRow, docx, docx.record().id());

        // The version carries the PDF as its document: that is the file a contract page links to.
        String label = "V" + (jdbc.queryForObject(
                "select count(*) from contract_versions where contract_id = ?", Integer.class,
                contractId) + 1);
        var version = versions.createVersion(contractId, tenantId, label, effectiveFrom(contractRow),
                CHANGE_NOTE, pdfDocumentId, actorId);

        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CONTRACT,
                        "CONTRACT_DOCUMENT_GENERATED")
                .tenant(tenantId)
                .entity("contracts", contractId, title)
                .description("Vertragsdokument aus Vorlage erzeugt")
                .changes(Map.of("templateId", templateId.toString(), "documentId",
                        pdfDocumentId.toString(), "versionId", version.getId().toString()))
                .build());

        log.info("Contract {} document generated from template {} (version {}, PDF {}, DOCX {})",
                contractId, templateId, version.getVersionLabel(), pdfDocumentId, docx.record().id());
        return new Generated(version.getId(), pdfDocumentId, pdf.record().id(), docx.record().id(),
                rendered.usedFields());
    }

    // ------------------------------------------------------------------ data

    /** The values for every field of the template, resolved from the contract and its surroundings. */
    private Map<String, String> resolveValues(Map<String, Object> contractRow, List<Field> fields) {
        UUID contractId = (UUID) contractRow.get("id");
        List<PartyData> parties = partiesOf(contractId);
        WorkspaceData workspace = workspaceOf(contractRow);
        Map<UUID, OrganizationData> organizations = organizationsOf(contractRow, parties);
        OrganizationData contractOrganization = organizations.get(contractRow.get("organization_id"));
        Map<UUID, ContactData> contacts = contactsOf(contractRow, parties);

        // The customer contact stored on the contract itself counts as a party when no party carries one,
        // so {{Kunde.*}} also works for contracts entered without parties.
        if (contractRow.get("counterparty_contact_id") != null) {
            boolean anyPartyHasContact = parties.stream().anyMatch(party -> party.contactId() != null);
            if (!anyPartyHasContact) {
                parties = new ArrayList<>(parties);
                parties.add(new PartyData("COUNTERPARTY", null,
                        (UUID) contractRow.get("counterparty_contact_id"), null, false));
            }
        }

        ContractData contract = new ContractData(string(contractRow.get("title")),
                string(contractRow.get("contract_number")), string(contractRow.get("contract_type")),
                string(contractRow.get("status")), string(contractRow.get("description")),
                date(contractRow.get("start_date")), date(contractRow.get("end_date")),
                date(contractRow.get("signed_date")), bool(contractRow.get("auto_renewal")),
                integer(contractRow.get("renewal_period_months")),
                integer(contractRow.get("notice_period_value")),
                string(contractRow.get("notice_period_unit")),
                date(contractRow.get("notice_deadline_date")), decimal(contractRow.get("cost_amount")),
                string(contractRow.get("cost_currency")), string(contractRow.get("cost_interval")));
        return ContractTemplateValues.values(contract, parties, workspace, contractOrganization,
                organizations, contacts, fields);
    }

    private List<PartyData> partiesOf(UUID contractId) {
        return jdbc.query("""
                select party_role, party_name, contact_id, organization_id, is_primary
                  from contract_parties where contract_id = ?
                  order by created_at asc, id asc
                """, (rs, row) -> new PartyData(rs.getString("party_role"), rs.getString("party_name"),
                rs.getObject("contact_id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getBoolean("is_primary")), contractId);
    }

    private WorkspaceData workspaceOf(Map<String, Object> contractRow) {
        UUID workspaceId = (UUID) contractRow.get("workspace_id");
        if (workspaceId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select name from workspaces where id = ? and deleted_at is null
                """, workspaceId);
        return rows.isEmpty() ? null : new WorkspaceData(string(rows.getFirst().get("name")));
    }

    /** The organisation of the contract and of every party, keyed by id. */
    private Map<UUID, OrganizationData> organizationsOf(Map<String, Object> contractRow,
                                                        List<PartyData> parties) {
        List<UUID> ids = new ArrayList<>();
        if (contractRow.get("organization_id") != null) {
            ids.add((UUID) contractRow.get("organization_id"));
        }
        for (PartyData party : parties) {
            if (party.organizationId() != null && !ids.contains(party.organizationId())) {
                ids.add(party.organizationId());
            }
        }
        Map<UUID, OrganizationData> byId = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        jdbc.query("""
                select id, name, street, house_number, postal_code, city from organizations
                 where id = any(?) and deleted_at is null
                """, rs -> {
            byId.put(rs.getObject("id", UUID.class), new OrganizationData(rs.getString("name"),
                    rs.getString("street"), rs.getString("house_number"), rs.getString("postal_code"),
                    rs.getString("city")));
        }, ids.toArray(new UUID[0]));
        return byId;
    }

    /** The contacts linked to the parties and to the contract, keyed by id, with their address. */
    private Map<UUID, ContactData> contactsOf(Map<String, Object> contractRow, List<PartyData> parties) {
        List<UUID> ids = new ArrayList<>();
        if (contractRow.get("counterparty_contact_id") != null) {
            ids.add((UUID) contractRow.get("counterparty_contact_id"));
        }
        for (PartyData party : parties) {
            if (party.contactId() != null && !ids.contains(party.contactId())) {
                ids.add(party.contactId());
            }
        }
        Map<UUID, ContactData> byId = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        jdbc.query("""
                select id, display_name, first_name, last_name, organization_name, email, phone
                  from contacts where id = any(?) and deleted_at is null
                """, rs -> {
            byId.put(rs.getObject("id", UUID.class), new ContactData(rs.getString("display_name"),
                    rs.getString("first_name"), rs.getString("last_name"),
                    rs.getString("organization_name"), rs.getString("email"), rs.getString("phone"),
                    null, null, null, null));
        }, ids.toArray(new UUID[0]));

        // The address of each contact, one row per contact: the primary address wins over the rest.
        Map<UUID, String[]> address = new LinkedHashMap<>();
        jdbc.query("""
                select contact_id, street, house_number, postal_code, city from contact_addresses
                 where contact_id = any(?)
                 order by case when is_primary then 0 else 1 end, created_at asc
                """, rs -> {
            UUID contactId = rs.getObject("contact_id", UUID.class);
            if (!address.containsKey(contactId)) {
                address.put(contactId, new String[] {rs.getString("street"),
                        rs.getString("house_number"), rs.getString("postal_code"),
                        rs.getString("city")});
            }
        }, ids.toArray(new UUID[0]));
        for (Map.Entry<UUID, String[]> entry : address.entrySet()) {
            ContactData base = byId.get(entry.getKey());
            if (base != null) {
                byId.put(entry.getKey(), new ContactData(base.displayName(), base.firstName(),
                        base.lastName(), base.organizationName(), base.email(), base.phone(),
                        entry.getValue()[0], entry.getValue()[1], entry.getValue()[2],
                        entry.getValue()[3]));
            }
        }
        return byId;
    }

    /** The fields of a template, for the tenant's own templates and the platform's alike. */
    private List<Field> fieldsOf(UUID tenantId, UUID templateId) {
        return jdbc.query("""
                select f.field_key, f.source_entity, f.source_path, f.is_required, f.default_value,
                       f.value_type
                  from template_fields f
                  join document_templates t on t.id = f.template_id
                 where f.template_id = ? and (t.tenant_id = ? or t.tenant_id is null)
                 order by f.sort_order
                """, (rs, row) -> new Field(rs.getString("field_key"), rs.getString("source_entity"),
                rs.getString("source_path"), rs.getBoolean("is_required"), rs.getString("default_value"),
                rs.getString("value_type")), templateId, tenantId);
    }

    // ------------------------------------------------------------------ storing the produced file

    /**
     * Stores the produced bytes as a document of the account and points the generated record at them, so
     * the file can be downloaded and found like any other document.
     */
    private UUID storeFile(UUID tenantId, UUID actorId, UUID contractId, UUID templateId,
                           Map<String, Object> contractRow,
                           GeneratedDocumentExportService.Produced produced, UUID generatedRecordId) {
        String contentHash = sha256(produced.content());
        // Two clicks in the same second would otherwise race the unique index below.
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> null, tenantId + ":" + contentHash);

        List<UUID> existing = jdbc.query("""
                select id from documents
                 where tenant_id = ? and content_hash = ? and deleted_at is null
                   and processing_status <> 'DUPLICATE'
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, contentHash);
        if (!existing.isEmpty()) {
            UUID documentId = existing.getFirst();
            UUID blobId = jdbc.queryForObject("""
                    select blob_id from document_versions where document_id = ?
                    order by version desc limit 1
                    """, UUID.class, documentId);
            link(generatedRecordId, tenantId, blobId, documentId);
            log.info("Generated file {} already stored as document {}", produced.filename(), documentId);
            return documentId;
        }

        BlobStore.StoredBlob stored = blobs.store(tenantId,
                new ByteArrayInputStream(produced.content()), produced.content().length,
                produced.mimeType());
        if (!stored.contentHash().equals(contentHash)) {
            throw new IllegalStateException(
                    "The stored file hash does not match the bytes that were written");
        }
        try {
            UUID blobId = jdbc.queryForObject("""
                    insert into document_blobs (id, tenant_id, storage_key, storage_backend, bucket,
                                                size_bytes, content_hash, content_type, scan_status,
                                                scan_details, quarantined)
                    values (gen_random_uuid(), ?, ?, ?, null, ?, ?, ?, 'SKIPPED',
                            jsonb_build_object('reason', 'Vom System erzeugt; kein Upload geprüft'), false)
                    returning id
                    """, UUID.class, tenantId, stored.storageKey(), stored.backend(), stored.sizeBytes(),
                    contentHash, produced.mimeType());

            UUID documentId = jdbc.queryForObject("""
                    insert into documents (id, tenant_id, workspace_id, original_filename, internal_name,
                                           mime_type, size_bytes, content_hash, current_version, source,
                                           processing_status, review_status, document_number, created_by,
                                           structured_data)
                    values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, 1, 'GENERATED', 'READY',
                            'NOT_REQUIRED', ?, ?, ?::jsonb)
                    returning id
                    """, UUID.class, tenantId, contractRow.get("workspace_id"), produced.filename(),
                    stored.storageKey(), produced.mimeType(), stored.sizeBytes(), contentHash,
                    string(contractRow.get("contract_number")), actorId,
                    json(Map.of("generated", "CONTRACT_TEMPLATE", "contractId", contractId.toString(),
                            "templateId", templateId.toString())));

            jdbc.update("""
                    insert into document_versions (id, document_id, tenant_id, version, blob_id,
                                                   content_hash, size_bytes, mime_type, original_filename,
                                                   change_note, created_by)
                    values (gen_random_uuid(), ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                    """, documentId, tenantId, blobId, contentHash, stored.sizeBytes(),
                    produced.mimeType(), produced.filename(), CHANGE_NOTE, actorId);

            jdbc.update("""
                    insert into document_sources (id, document_id, tenant_id, source_type, raw_metadata)
                    values (gen_random_uuid(), ?, ?, 'GENERATED', ?::jsonb)
                    """, documentId, tenantId,
                    json(Map.of("generated", "CONTRACT_TEMPLATE", "contractId", contractId.toString())));

            link(generatedRecordId, tenantId, blobId, documentId);
            log.info("Generated contract file {} stored as document {} ({} bytes)", produced.filename(),
                    documentId, stored.sizeBytes());
            return documentId;
        }
        catch (RuntimeException ex) {
            blobs.delete(stored.storageKey());
            throw ex;
        }
    }

    /** The generated record points at the file that belongs to it, which is what the download route reads. */
    private void link(UUID generatedRecordId, UUID tenantId, UUID blobId, UUID documentId) {
        jdbc.update("""
                update generated_documents
                   set rendered_blob_id = ?, rendered_document_id = ?
                 where id = ? and tenant_id = ?
                """, blobId, documentId, generatedRecordId, tenantId);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> requireRow(String sql, String what, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw ApiException.notFound(what);
        }
        return rows.getFirst();
    }

    private LocalDate effectiveFrom(Map<String, Object> contractRow) {
        LocalDate start = date(contractRow.get("start_date"));
        return start != null ? start : LocalDate.now(ZoneId.of("Europe/Berlin"));
    }

    /**
     * The rendered HTML as document blocks. The editor produces plain block markup ({@code <p>}, {@code <div>},
     * {@code <h1>..<h6>}, {@code <ul><li>}); headings keep their level, everything else is a paragraph.
     */
    static List<RichDocument.Block> htmlBlocks(String html) {
        String marked = (html == null ? "" : html)
                .replaceAll("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1>", "\u0001$1$2\u0002")
                .replaceAll("(?is)<(p|div|li|tr|table|ul|ol|thead|tbody)\\b[^>]*>", "\n")
                .replaceAll("(?is)</(p|div|li|tr|table|ul|ol|thead|tbody|h[1-6])\\s*>", "\n")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?s)<[^>]+>", "");
        marked = unescape(marked);

        List<RichDocument.Block> blocks = new ArrayList<>();
        for (String line : marked.split("\n")) {
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.startsWith("\u0001") && text.indexOf('\u0002') > 1) {
                int level = Math.max(1, Math.min(6, Character.digit(text.charAt(1), 10)));
                String heading = text.substring(2, text.indexOf('\u0002')).trim();
                if (!heading.isEmpty()) {
                    blocks.add(new RichDocument.Block.Heading(level, heading));
                }
            }
            else {
                blocks.add(new RichDocument.Block.Paragraph(text));
            }
        }
        return List.copyOf(blocks);
    }

    /** The named entities a browser-written template can contain. */
    static String unescape(String text) {
        return text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 must exist on every JVM", ex);
        }
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("A map cannot fail to serialise", ex);
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDate date(Object value) {
        return value instanceof LocalDate date ? date : null;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : null;
    }
}

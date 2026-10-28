package de.buerokratiecopilot.templates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Keeping the documents that were generated (spec §15, E-003).
 *
 * <p>A letter that was produced and then forgotten is a letter nobody can prove was sent. The schema has
 * carried the table for this from the beginning and nothing wrote to it; this closes that.</p>
 *
 * <p><strong>A document with unfilled fields is not a finished document.</strong> The schema has a status
 * for exactly this — {@code BLOCKED_UNRESOLVED_FIELDS} — and using it matters more than it looks: a
 * finished-looking letter with a blank where the amount should be is sent, filed, and discovered months
 * later by somebody who has to reconstruct what was meant. The status is derived from what is actually
 * unresolved rather than passed in, so a caller cannot announce a document as ready while it has holes.</p>
 *
 * <p><strong>What was generated is kept as it was.</strong> The template may be edited afterwards; the
 * letter that went out must not change with it. The version is recorded next to the document for the same
 * reason the workflow records the version of a process.</p>
 */
@Service
public class GeneratedDocumentService {

    private static final Logger LOG = LoggerFactory.getLogger(GeneratedDocumentService.class);

    private static final List<String> FORMATS = List.of("PDF", "DOCX", "HTML", "TXT");

    private final JdbcTemplate jdbc;

    public GeneratedDocumentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What a caller supplies. */
    public record Request(UUID templateId, UUID templateVersionId, UUID caseId, String subjectType,
                          UUID subjectId, String title, String outputFormat, String bodyHtml,
                          Map<String, Object> fieldValues, List<String> unresolvedFields) {
    }

    /** A stored document. */
    public record Stored(UUID id, String status, String title, String outputFormat,
                         List<String> unresolvedFields) {

        public boolean isReady() {
            return "GENERATED".equals(status);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id.toString());
            map.put("status", status);
            map.put("title", title);
            map.put("outputFormat", outputFormat);
            map.put("unresolvedFields", unresolvedFields);
            map.put("ready", isReady());
            return map;
        }
    }

    /**
     * Records a generated document.
     *
     * <p>The status follows from the unresolved fields. A caller that wants a document marked ready has to
     * fill it in; there is no parameter to assert it.</p>
     */
    @Transactional
    public Stored record(UUID tenantId, Request request, UUID generatedBy) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new ApiException(de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST,
                    "Ein erzeugtes Dokument braucht einen Titel");
        }
        String format = (request.outputFormat() == null ? "PDF"
                : request.outputFormat().toUpperCase(java.util.Locale.ROOT));
        if (!FORMATS.contains(format)) {
            throw new ApiException(de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST,
                    "Unbekanntes Ausgabeformat: " + request.outputFormat());
        }

        List<String> unresolved = request.unresolvedFields() == null ? List.of()
                : request.unresolvedFields().stream().filter(field -> field != null && !field.isBlank())
                        .toList();
        // Derived, not accepted from the caller: a document with holes must not be able to look finished.
        String status = unresolved.isEmpty() ? "GENERATED" : "BLOCKED_UNRESOLVED_FIELDS";

        UUID id = jdbc.queryForObject("""
                insert into generated_documents (id, tenant_id, template_id, template_version_id, case_id,
                                                 subject_type, subject_id, title, output_format, status,
                                                 body_html, field_values, unresolved_fields, generated_by,
                                                 generated_at)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, now())
                returning id
                """, UUID.class, tenantId, request.templateId(), request.templateVersionId(),
                request.caseId(), request.subjectType(), request.subjectId(), request.title().strip(),
                format, status, request.bodyHtml(),
                json(request.fieldValues() == null ? Map.of() : request.fieldValues()),
                json(unresolved), generatedBy);

        LOG.info("Generated document {} recorded as {} ({} unresolved field(s))", id, status,
                unresolved.size());
        return new Stored(id, status, request.title().strip(), format, unresolved);
    }

    /** The documents generated for one subject, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forSubject(UUID tenantId, String subjectType, UUID subjectId) {
        return jdbc.queryForList("""
                select id, title, output_format, status, generated_at, unresolved_fields
                from generated_documents
                where tenant_id = ? and subject_type = ? and subject_id = ?
                order by generated_at desc
                """, tenantId, subjectType, subjectId);
    }

    /** The documents generated from one template. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forTemplate(UUID tenantId, UUID templateId) {
        return jdbc.queryForList("""
                select id, title, output_format, status, template_version_id, generated_at
                from generated_documents where tenant_id = ? and template_id = ?
                order by generated_at desc
                """, tenantId, templateId);
    }

    /** One document. */
    @Transactional(readOnly = true)
    public Map<String, Object> describe(UUID tenantId, UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, title, output_format, status, body_html, field_values, unresolved_fields,
                       template_id, template_version_id, generated_at
                from generated_documents where id = ? and tenant_id = ?
                """, documentId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("generated document");
        }
        return rows.getFirst();
    }

    /** How many documents are waiting on somebody to fill them in. */
    @Transactional(readOnly = true)
    public int blockedCount(UUID tenantId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from generated_documents
                where tenant_id = ? and status = 'BLOCKED_UNRESOLVED_FIELDS'
                """, Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    /** The documents of a tenant, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId, int limit) {
        return jdbc.queryForList("""
                select id, title, output_format, status, subject_type, subject_id, generated_at
                from generated_documents where tenant_id = ? order by generated_at desc limit ?
                """, tenantId, Math.max(1, Math.min(limit, 200)));
    }

    /** The unresolved fields of a document, which is the list of what is missing. */
    @Transactional(readOnly = true)
    public List<String> unresolvedFields(UUID tenantId, UUID documentId) {
        Object raw = describe(tenantId, documentId).get("unresolved_fields");
        List<String> fields = new ArrayList<>();
        // A jsonb column reaches the driver in more than one shape, so all of them are handled rather than
        // whichever one this driver happens to use today.
        Object parsed = raw;
        if (!(raw instanceof List<?>) && raw != null) {
            // A jsonb column arrives as text or as a driver object, depending on the driver; reading it as
            // text covers both without depending on a class that is only on the runtime classpath.
            String text = String.valueOf(raw);
            parsed = text.isBlank() ? List.of() : readAnyJson(text);
        }
        if (parsed instanceof List<?> list) {
            list.forEach(field -> {
                if (field != null && !String.valueOf(field).isBlank()) {
                    fields.add(String.valueOf(field));
                }
            });
        }
        return List.copyOf(fields);
    }

    /**
     * Reads a stored list back.
     *
     * <p>Parsed as a list rather than through the workflow reader, which expects an object: the unresolved
     * fields are an array, and asking an object reader for an array is how the list came back empty.</p>
     */
    static Object readAnyJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Die gespeicherte Liste ist nicht lesbar", ex);
        }
    }

    private static String json(Object value) {
        return de.buerokratiecopilot.workflow.WorkflowService.toJson(value);
    }
}

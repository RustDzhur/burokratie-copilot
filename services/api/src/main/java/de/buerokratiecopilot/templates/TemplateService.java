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
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.templates.TemplateRenderer.Field;

/**
 * Document templates and their fields (spec §12, E-003).
 *
 * <p>The acceptance criterion is that an unknown variable blocks generation, and this class is where
 * that becomes a refusal rather than a report: {@link #generate} throws when the renderer cannot resolve
 * everything, and no document is written. A template engine that returns a partially filled document
 * and a warning has produced the document, and somebody will send it.</p>
 *
 * <p>A field declares where its value comes from — an entity and a path — and the catalogue of what may
 * be addressed is what a template is allowed to ask for. A template that asks for something else is
 * edited, not guessed at.</p>
 */
@Service
public class TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);

    /** The entities a field may draw from, as the schema allows. */
    public static final List<String> SOURCE_ENTITIES = List.of("USER", "ORGANIZATION", "CONTACT", "CASE",
            "CONTRACT", "INVOICE", "PAYMENT", "TASK", "CUSTOM",
            // Contract document sources (V72): the contract record, its parties, the workspace and the
            // customer contact. Named the way a template addresses them: {{Vertrag.Title}}, {{Partei.Name}}.
            "PARTEI", "VERTRAG", "WERK", "KUNDE");

    /** The categories the schema allows. */
    public static final List<String> CATEGORIES = List.of("LETTER", "APPLICATION", "OBJECTION",
            "TERMINATION", "REMINDER", "COMPLAINT", "INVOICE", "CONTRACT", "INTERNAL", "OTHER");

    private final JdbcTemplate jdbc;

    public TemplateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A field as it is defined. */
    public record FieldRequest(String fieldKey, String labelGerman, String sourceEntity, String sourcePath,
                               String valueType, boolean required, String defaultValue) {
    }

    /** A template with its first version. */
    public record TemplateRequest(UUID tenantId, String code, String nameGerman, String descriptionGerman,
                                  String category, List<FieldRequest> fields, String bodyHtml,
                                  String subjectTemplate, UUID createdBy) {
    }

    /** What a generation produced. */
    public record Generated(UUID documentId, String subject, String body, List<String> usedFields) {
    }

    /**
     * Defines a template with its fields and a first draft version.
     *
     * <p>Every placeholder in the body must correspond to a declared field. A body that asks for
     * something the catalogue does not have is refused at definition time rather than at generation
     * time, so the mistake is found by the person editing the template and not by whoever is trying to
     * send a letter.</p>
     */
    @Transactional
    public UUID define(TemplateRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Vorlage braucht einen Code");
        }
        String category = request.category() == null ? "OTHER"
                : request.category().toUpperCase(java.util.Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Kategorie: " + category);
        }

        List<Field> declared = new ArrayList<>();
        for (FieldRequest field : request.fields() == null ? List.<FieldRequest>of() : request.fields()) {
            if (field.fieldKey() == null || field.fieldKey().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Feld braucht einen Schlüssel");
            }
            String entity = field.sourceEntity() == null ? "CUSTOM"
                    : field.sourceEntity().toUpperCase(java.util.Locale.ROOT);
            if (!SOURCE_ENTITIES.contains(entity)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Quelle: " + entity);
            }
            declared.add(new Field(field.fieldKey(), entity, field.sourcePath(), field.required(),
                    field.defaultValue(), field.valueType()));
        }

        // The body is checked against the catalogue before anything is stored: a placeholder nobody can
        // resolve is a fault in the template, and saying so here is cheaper than saying it later.
        List<String> unknown = new ArrayList<>();
        for (String placeholder : TemplateRenderer.placeholdersIn(request.bodyHtml())) {
            boolean declaredHere = declared.stream()
                    .anyMatch(field -> field.placeholder().equalsIgnoreCase(placeholder)
                            || field.fieldKey().equalsIgnoreCase(placeholder));
            if (!declaredHere && !unknown.contains(placeholder)) {
                unknown.add(placeholder);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Vorlage verwendet unbekannte Platzhalter: " + String.join(", ", unknown)
                            + ". Sie sind im Feldkatalog der Vorlage nicht definiert.");
        }

        UUID templateId = UUID.randomUUID();
        jdbc.update("""
                insert into document_templates (id, tenant_id, code, name, description, category,
                                                field_scope, is_active, current_version, created_by)
                values (?, ?, ?, ?::jsonb, ?::jsonb, ?, '{}'::jsonb, true, 1, ?)
                """, templateId, request.tenantId(), request.code().strip(),
                jsonOf(request.nameGerman()), jsonOf(request.descriptionGerman()), category,
                request.createdBy());

        int order = 0;
        for (FieldRequest field : request.fields() == null ? List.<FieldRequest>of() : request.fields()) {
            jdbc.update("""
                    insert into template_fields (id, template_id, field_key, label, source_entity,
                                                 source_path, value_type, is_required, default_value,
                                                 sort_order)
                    values (gen_random_uuid(), ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                    """, templateId, field.fieldKey().strip(), jsonOf(field.labelGerman()),
                    field.sourceEntity() == null ? "CUSTOM"
                            : field.sourceEntity().toUpperCase(java.util.Locale.ROOT),
                    // source_path is mandatory in the table; a hand-filled field has no path of its own, so
                    // it is addressed by its key.
                    field.sourcePath() == null || field.sourcePath().isBlank() ? field.fieldKey().strip()
                            : field.sourcePath(),
                    field.valueType() == null ? "TEXT" : field.valueType(),
                    field.required(), field.defaultValue(), order++);
        }

        jdbc.update("""
                insert into template_versions (id, template_id, tenant_id, version, status, language,
                                               body_model, body_html, header_html, footer_html,
                                               page_settings, subject_template, created_by)
                values (gen_random_uuid(), ?, ?, 1, 'DRAFT', 'de', '{}'::jsonb, ?, null, null,
                        '{}'::jsonb, ?, ?)
                """, templateId, request.tenantId(), request.bodyHtml(), request.subjectTemplate(),
                request.createdBy());

        log.info("Template {} defined for tenant {} with {} fields", templateId, request.tenantId(),
                declared.size());
        return templateId;
    }

    /**
     * The templates this account can use: its own and the platform's, with whether a version is published.
     * Platform templates carry no tenant; a row of another tenant never appears.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Map<String, Object>> list(
            UUID tenantId, String query, List<String> statuses, String category,
            org.springframework.data.domain.Pageable pageable) {
        String term = query == null ? "" : query.strip();
        List<String> wanted = statuses == null || statuses.isEmpty() ? List.of("DRAFT", "PUBLISHED") : statuses;
        for (String status : wanted) {
            if (!List.of("DRAFT", "PUBLISHED").contains(status)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Status: " + status);
            }
        }
        String wantedCategory = category == null || category.isBlank() ? ""
                : category.toUpperCase(java.util.Locale.ROOT);
        if (!wantedCategory.isEmpty() && !CATEGORIES.contains(wantedCategory)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Kategorie: " + category);
        }
        String base = """
                from (
                  select t.id, coalesce(t.name->>'de', t.name->>'en', t.code) as name, t.code, t.category,
                         case when exists (select 1 from template_versions v
                                            where v.template_id = t.id and v.status = 'PUBLISHED')
                              then 'PUBLISHED' else 'DRAFT' end as status,
                         t.updated_at
                  from document_templates t
                  where t.is_active and (t.tenant_id = ? or t.tenant_id is null)
                ) u
                where u.status in (?, ?) and (? = '' or lower(u.name) like '%' || lower(?) || '%')
                  and (? = '' or u.category = ?)
                """;
        Object[] arguments = {tenantId, wanted.contains("DRAFT") ? "DRAFT" : "-",
                wanted.contains("PUBLISHED") ? "PUBLISHED" : "-", term, term, wantedCategory,
                wantedCategory};
        Long total = jdbc.queryForObject("select count(*) " + base, Long.class, arguments);
        Object[] paged = java.util.Arrays.copyOf(arguments, arguments.length + 2);
        paged[arguments.length] = pageable.getPageSize();
        paged[arguments.length + 1] = pageable.getOffset();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select u.id, u.name, u.code, u.category, u.status, u.updated_at " + base
                        + " order by u.name limit ? offset ?", paged);
        return new org.springframework.data.domain.PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** Publishes a version, which is what makes it usable for generation. */
    @Transactional
    public void publish(UUID tenantId, UUID templateId, int version, UUID publisherId) {
        if (publisherId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Veröffentlichen braucht eine Person");
        }
        int updated = jdbc.update("""
                update template_versions set status = 'PUBLISHED', published_by = ?, published_at = now()
                where template_id = ? and tenant_id = ? and version = ?
                """, publisherId, templateId, tenantId, version);
        if (updated == 0) {
            throw ApiException.notFound("template version");
        }
    }

    /**
     * Generates a document from a template.
     *
     * @throws ApiException when a placeholder is unknown or a required field has no value; in both cases
     *                      nothing is written, because a half-filled letter is a letter somebody sends
     */
    @Transactional
    public Generated generate(UUID tenantId, UUID templateId, Map<String, String> values) {
        Map<String, Object> template = jdbc.queryForMap("""
                select code, name, current_version from document_templates
                where id = ? and tenant_id = ? and is_active = true
                """, templateId, tenantId);

        int version = ((Number) template.get("current_version")).intValue();
        Map<String, Object> versionRow = jdbc.queryForMap("""
                select body_html, subject_template, status from template_versions
                where template_id = ? and tenant_id = ? and version = ?
                """, templateId, tenantId, version);

        if (!"PUBLISHED".equals(String.valueOf(versionRow.get("status")))) {
            throw ApiException.conflict("Diese Vorlagenversion ist noch nicht veröffentlicht und wird "
                    + "nicht zur Erzeugung verwendet");
        }

        List<Field> fields = fieldsOf(tenantId, templateId);
        TemplateRenderer.Result result = TemplateRenderer.render(
                versionRow.get("body_html") == null ? "" : String.valueOf(versionRow.get("body_html")),
                fields, values);

        if (!result.isRenderable()) {
            // The acceptance criterion. The report names every problem, so one attempt is enough to fix
            // them all.
            throw new ApiException(ErrorCode.INVALID_REQUEST, result.problem());
        }

        TemplateRenderer.Result subject = TemplateRenderer.render(
                versionRow.get("subject_template") == null ? ""
                        : String.valueOf(versionRow.get("subject_template")),
                fields, values);
        if (!subject.isRenderable()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, subject.problem());
        }

        log.info("Document generated from template {} for tenant {}", templateId, tenantId);
        return new Generated(null, subject.text(), result.text(), result.usedFields());
    }

    /** The fields of a template, in the order they were defined. */
    @Transactional(readOnly = true)
    public List<Field> fieldsOf(UUID tenantId, UUID templateId) {
        return jdbc.query("""
                select f.field_key, f.source_entity, f.source_path, f.is_required, f.default_value,
                       f.value_type
                from template_fields f
                join document_templates t on t.id = f.template_id
                where f.template_id = ? and t.tenant_id = ?
                order by f.sort_order
                """, (rs, row) -> new Field(rs.getString("field_key"), rs.getString("source_entity"),
                rs.getString("source_path"), rs.getBoolean("is_required"), rs.getString("default_value"),
                rs.getString("value_type")), templateId, tenantId);
    }

    /** The catalogue a template may draw from, for the insert panel. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> catalogue(UUID tenantId, UUID templateId) {
        List<Map<String, Object>> catalogue = new ArrayList<>();
        for (Field field : fieldsOf(tenantId, templateId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("placeholder", field.placeholder());
            entry.put("fieldKey", field.fieldKey());
            entry.put("sourceEntity", field.sourceEntity());
            entry.put("required", field.required());
            entry.put("valueType", field.valueType());
            catalogue.add(entry);
        }
        return catalogue;
    }

    /**
     * {@code {"de": value}} with the value escaped by a JSON writer. Hand-built quoting breaks on a backslash
     * or a line break in a name a person typed, and it would also let a crafted name add keys of its own.
     */
    private static String jsonOf(String value) {
        try {
            return JSON.writeValueAsString(java.util.Map.of("de", value == null ? "" : value));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("A string map cannot fail to serialise", ex);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
}

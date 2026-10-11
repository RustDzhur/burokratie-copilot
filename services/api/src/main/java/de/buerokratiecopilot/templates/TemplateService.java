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
            "CONTRACT", "INVOICE", "PAYMENT", "TASK", "CUSTOM");

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
                    field.sourcePath(), field.valueType() == null ? "TEXT" : field.valueType(),
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

    private static String jsonOf(String value) {
        return "{\"de\":\"" + (value == null ? "" : value.replace("\"", "'")) + "\"}";
    }
}

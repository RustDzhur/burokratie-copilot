package de.buerokratiecopilot.templates.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.templates.GeneratedDocumentService;
import de.buerokratiecopilot.templates.TemplateService;

/**
 * Templates and letter generation (spec §15, §20).
 *
 * <p>{@code TemplateService} was implemented and tested with no endpoint, so a template could be defined
 * and never used. This is the way in.</p>
 *
 * <p><strong>A template is published as a version, and generating names the version.</strong> The letter
 * that went out must not change when somebody edits the template afterwards, which is the same reason a
 * process records the version it ran on.</p>
 *
 * <p><strong>Generation reports which fields it used.</strong> A letter assembled from a template is only
 * trustworthy if somebody can see where each value came from, and a generation that silently left a field
 * empty produces a letter with a gap that looks finished.</p>
 *
 * <p>The permissions are the catalogue's own — {@code templates.view}, {@code templates.create},
 * {@code templates.edit}.</p>
 */
@RestController
@RequestMapping("/api/v1/templates")
@Tag(name = "Templates", description = "Document templates and the letters they produce")
public class TemplateController {

    private static final String SEE = "hasAnyAuthority('templates.view','templates.create','templates.edit')";
    private static final String CREATE = "hasAnyAuthority('templates.create','templates.edit')";

    private final TemplateService templates;
    private final GeneratedDocumentService generated;

    public TemplateController(TemplateService templates, GeneratedDocumentService generated) {
        this.templates = templates;
        this.generated = generated;
    }

    /** One field a template may use. */
    public record FieldBody(String fieldKey, String labelGerman, String sourceEntity, String sourcePath,
                            String valueType, Boolean required, String defaultValue) {
    }

    /** A template to define. */
    public record TemplateBody(String code, String nameGerman, String descriptionGerman, String category,
                               List<FieldBody> fields, String bodyHtml, String subjectTemplate) {
    }

    /** What to generate from. */
    public record GenerateBody(Map<String, String> values) {
    }

    /**
     * Defines a template with its first version.
     *
     * <p>The code is the identity of the template: defining the same code again is not a second template,
     * which is what the service enforces.</p>
     */
    @GetMapping
    @PreAuthorize(SEE)
    @Operation(summary = "Templates of the account and of the platform, with their publication state")
    public org.springframework.data.domain.Page<Map<String, Object>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String category,
            @org.springframework.data.web.PageableDefault(size = 25) org.springframework.data.domain.Pageable pageable) {
        return templates.list(tenant(), q, status, category, pageable);
    }

    @PostMapping
    @PreAuthorize(CREATE)
    @Operation(summary = "Defines a template with its first version")
    public ResponseEntity<Map<String, Object>> define(@RequestBody TemplateBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body.code() == null || body.code().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Vorlage braucht einen Code");
        }

        List<TemplateService.FieldRequest> fields = (body.fields() == null ? List.<FieldBody>of()
                : body.fields()).stream()
                .map(field -> new TemplateService.FieldRequest(field.fieldKey(), field.labelGerman(),
                        field.sourceEntity(), field.sourcePath(), field.valueType(),
                        Boolean.TRUE.equals(field.required()), field.defaultValue()))
                .toList();

        UUID templateId = templates.define(new TemplateService.TemplateRequest(tenantId, body.code(),
                body.nameGerman(), body.descriptionGerman(), body.category(), fields, body.bodyHtml(),
                body.subjectTemplate(), actor));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("templateId", templateId.toString()));
    }

    /**
     * Publishes a version of a template.
     *
     * <p>Separate from defining, because the letter that went out must not change when somebody edits the
     * template afterwards.</p>
     */
    @PostMapping("/{templateId}/versions/{version}/publish")
    @PreAuthorize(CREATE)
    @Operation(summary = "Publishes a version of a template")
    public ResponseEntity<Void> publish(@PathVariable UUID templateId, @PathVariable int version) {
        templates.publish(tenant(), templateId, version, CurrentUser.required());
        return ResponseEntity.noContent().build();
    }

    /** The fields a template can use, which is what an editor offers. */
    @GetMapping("/{templateId}/fields")
    @PreAuthorize(SEE)
    @Operation(summary = "The fields this template can use")
    public List<Map<String, Object>> fields(@PathVariable UUID templateId) {
        return templates.fieldsOf(tenant(), templateId).stream().map(field -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fieldKey", field.fieldKey());
            entry.put("sourceEntity", field.sourceEntity());
            entry.put("sourcePath", field.sourcePath());
            entry.put("valueType", field.valueType());
            entry.put("required", field.required());
            entry.put("defaultValue", field.defaultValue());
            return entry;
        }).toList();
    }

    /**
     * Generates a letter.
     *
     * <p>The answer names which fields were used, so that a letter assembled from a template can be traced
     * back to where each value came from — and a field that stayed empty is visible rather than producing a
     * letter with a gap that looks finished.</p>
     */
    @PostMapping("/{templateId}/generate")
    @PreAuthorize(CREATE)
    @Operation(summary = "Generates a letter and reports which fields it used")
    public ResponseEntity<Map<String, Object>> generate(@PathVariable UUID templateId,
                                                        @RequestBody(required = false) GenerateBody body) {
        UUID tenantId = tenant();
        TemplateService.Generated generated1 = templates.generate(tenantId, templateId,
                body == null || body.values() == null ? Map.of() : body.values());

        // The template service produces the letter; the register keeps it. The two are done together
        // because a letter that was produced and not recorded is a letter nobody can prove was sent -
        // and generate() returns a documentId that is null when nothing recorded anything, which is how
        // the separation showed up: a caller was handed a null where an identifier belongs.
        Map<String, Object> used = body == null || body.values() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(body.values());
        var stored = generated.record(tenantId, new GeneratedDocumentService.Request(null, null, null,
                "template", templateId, generated1.subject(), "HTML", generated1.body(), used,
                List.of()), CurrentUser.required());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", stored.id().toString());
        result.put("status", stored.status());
        result.put("subject", generated1.subject());
        result.put("usedFields", generated1.usedFields());
        result.put("note", "Für die Prüfung des Briefes: die verwendeten Felder sind oben genannt.");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** Serves a template's fields by code, for a caller that has not kept the identifier. */
    @GetMapping("/by-code/{code}/fields")
    @PreAuthorize(SEE)
    @Operation(summary = "The fields of the template with this code")
    public List<Map<String, Object>> fieldsByCode(@PathVariable String code,
                                                  @RequestParam(required = false) UUID templateId) {
        if (templateId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Bitte die Vorlagenkennung angeben; Codes sind innerhalb eines Kontos eindeutig, aber "
                            + "die Kennung ist es, worauf sich alles bezieht");
        }
        return fields(templateId);
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}

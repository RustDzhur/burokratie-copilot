package de.buerokratiecopilot.documents.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.documents.explain.DocumentExplanationService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/** The Translator: the explanation of a document and its translation, kept apart from the document itself. */
@RestController
@RequestMapping("/api/v1/documents/{id}")
public class ExplanationController {

    private final DocumentExplanationService service;

    public ExplanationController(DocumentExplanationService service) {
        this.service = service;
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
    }

    @GetMapping("/explanation")
    @PreAuthorize("hasAuthority('documents.view')")
    public ResponseEntity<Map<String, Object>> explanation(@PathVariable UUID id, @RequestParam(defaultValue = "de") String language) {
        Map<String, Object> stored = service.latest(tenant(), id, "EXPLAIN", language, false);
        return stored == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(stored);
    }

    @PostMapping("/explanation")
    @PreAuthorize("hasAuthority('documents.view')")
    public Map<String, Object> explain(@PathVariable UUID id, @RequestParam(defaultValue = "de") String language) {
        return service.explain(tenant(), CurrentUser.required(), id, language).asMap();
    }

    @GetMapping("/translation")
    @PreAuthorize("hasAuthority('documents.view')")
    public ResponseEntity<Map<String, Object>> translation(@PathVariable UUID id, @RequestParam String language,
                                                           @RequestParam(defaultValue = "false") boolean simple) {
        Map<String, Object> stored = service.latest(tenant(), id, "TRANSLATE", language, simple);
        return stored == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(stored);
    }

    @PostMapping("/translation")
    @PreAuthorize("hasAuthority('documents.view')")
    public Map<String, Object> translate(@PathVariable UUID id, @RequestParam String language,
                                         @RequestParam(defaultValue = "false") boolean simple) {
        return service.translate(tenant(), CurrentUser.required(), id, language, simple).asMap();
    }
}

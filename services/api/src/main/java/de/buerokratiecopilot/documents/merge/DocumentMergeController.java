package de.buerokratiecopilot.documents.merge;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Proposes which uploaded pages belong together, and merges pages into one document when a person asks for it. */
@RestController
@RequestMapping("/api/v1/documents")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "Documents", description = "Upload, list, inspect and download documents")
public class DocumentMergeController {

    private final DocumentMergeService merging;

    public DocumentMergeController(DocumentMergeService merging) {
        this.merging = merging;
    }

    public record MergeRequest(List<UUID> documentIds, String title) {
    }

    @GetMapping("/merge-candidates")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "Groups of uploaded photos that probably are the pages of one document")
    public List<DocumentMergeService.Group> candidates() {
        return merging.candidates();
    }

    @PostMapping("/merge")
    @PreAuthorize("hasAuthority('documents.create') and hasAuthority('documents.delete')")
    @Operation(summary = "Make one PDF of the given pages, in the given order, and delete the single pages")
    public ResponseEntity<Map<String, Object>> merge(@RequestBody MergeRequest request) {
        var merged = merging.merge(request == null ? null : request.documentIds(), request == null ? null : request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("documentId", merged.getId(), "filename", merged.getOriginalFilename()));
    }
}

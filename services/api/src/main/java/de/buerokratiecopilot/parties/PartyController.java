package de.buerokratiecopilot.parties;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The people or the organisation a cabinet belongs to, and whom a document is addressed to. */
@RestController
@RequestMapping("/api/v1")
public class PartyController {

    public record PartyRequest(String displayName, String relation, LocalDate birthDate, String aliases,
                               String address, String identifiers, String notes) {

        PartyService.PartyInput toInput() {
            return new PartyService.PartyInput(displayName, relation, birthDate, aliases, address, identifiers, notes);
        }
    }

    public record AssignRequest(UUID partyId) {
    }

    private final PartyService parties;

    public PartyController(PartyService parties) {
        this.parties = parties;
    }

    @GetMapping("/parties")
    @PreAuthorize("hasAuthority('documents.view')")
    public List<PartyService.Party> list() {
        return parties.list();
    }

    @PostMapping("/parties")
    @PreAuthorize("hasAuthority('documents.edit')")
    public ResponseEntity<java.util.Map<String, UUID>> create(@RequestBody PartyRequest request) {
        return ResponseEntity.status(201).body(java.util.Map.of("id", parties.create(request.toInput())));
    }

    @PutMapping("/parties/{id}")
    @PreAuthorize("hasAuthority('documents.edit')")
    public ResponseEntity<Void> update(@PathVariable UUID id, @RequestBody PartyRequest request) {
        parties.update(id, request.toInput());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/parties/{id}")
    @PreAuthorize("hasAuthority('documents.edit')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        parties.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/documents/{documentId}/party")
    @PreAuthorize("hasAuthority('documents.edit')")
    public ResponseEntity<Void> assign(@PathVariable UUID documentId, @RequestBody AssignRequest request) {
        parties.assign(documentId, request.partyId());
        return ResponseEntity.noContent().build();
    }
}

package de.buerokratiecopilot.contacts.web;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.contacts.ContactService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** Contacts of the account: people, organisations and authorities (spec §9, §20: {@code /contacts}). */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/contacts")
@Tag(name = "Contacts", description = "People, organisations and authorities")
public class ContactController {

    private final ContactService contacts;

    public ContactController(ContactService contacts) {
        this.contacts = contacts;
    }

    public record CreateContactRequest(String name, ContactService.Kind kind, String email, String phone) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('contacts.view')")
    @Operation(summary = "Contacts of the account, searchable and filterable by kind")
    public Page<Map<String, Object>> list(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) List<ContactService.Kind> kind,
                                          @PageableDefault(size = 25) Pageable pageable) {
        return contacts.list(RequestContext.tenantId().orElseThrow(), q, kind, pageable);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('contacts.create')")
    @Operation(summary = "Creates a contact")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateContactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contacts.create(
                RequestContext.tenantId().orElseThrow(),
                new ContactService.Draft(request.name(), request.kind(), request.email(), request.phone()),
                CurrentUser.required()));
    }
}

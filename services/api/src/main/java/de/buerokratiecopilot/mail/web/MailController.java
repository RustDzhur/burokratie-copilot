package de.buerokratiecopilot.mail.web;

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
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.mail.inbound.MailAccountService;
import de.buerokratiecopilot.mail.outbound.DeliverySemantics;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Mailboxes and outgoing mail (spec §12, §28: Postausgang).
 *
 * <p>{@code MailAccountService} and {@code OutboundMessageService} were implemented and tested with no
 * endpoint, so a mailbox could not be connected and a letter could not leave.</p>
 *
 * <p><strong>A mailbox password is deliberately not reachable over HTTP.</strong> {@code passwordOf} exists
 * because the ingester needs it in-process. An endpoint returning it would put a mailbox password into a
 * browser session, into whatever proxy sits in front of it, and into every screenshot of that page — and the
 * person who wants it can read it in their mail provider. This is the one place in this codebase where an
 * existing service method is intentionally left without a route, and the reason is written down rather than
 * left to be re-derived.</p>
 *
 * <p><strong>Sending is a handover, and the answer says what the provider did.</strong> The service returns
 * the provider's own identifier and status, and the endpoint passes them through: a letter that was handed
 * over and a letter that was delivered are not the same fact, and an interface that showed them as one would
 * be telling somebody their objection had arrived when it had not.</p>
 *
 * <p><strong>Every draft is idempotent by the caller's key.</strong> A retry after a timeout must not post a
 * second letter, and only the caller knows what its retry is a retry of — so the key is passed through rather
 * than defaulted.</p>
 */
@RestController
@RequestMapping("/api/v1/mail")
@Tag(name = "Mail", description = "Mailboxes, drafts and outgoing mail")
public class MailController {

    private static final String SEE = "hasAnyAuthority('mail.view','mail.configure','mail.send')";
    private static final String CONNECT = "hasAnyAuthority('mail.connect','mail.configure')";
    private static final String SEND = "hasAuthority('mail.send')";

    private final MailAccountService accounts;
    private final OutboundMessageService outbound;
    private final de.buerokratiecopilot.mail.MailListService messages;

    public MailController(MailAccountService accounts, OutboundMessageService outbound,
                          de.buerokratiecopilot.mail.MailListService messages) {
        this.accounts = accounts;
        this.outbound = outbound;
        this.messages = messages;
    }

    /** A mailbox to connect. */
    public record ConnectBody(UUID workspaceId, String displayName, String emailAddress, String provider,
                              String imapHost, Integer imapPort, String imapUsername, Boolean imapUseSsl,
                              String smtpHost, Integer smtpPort, String smtpUsername, String password) {
    }

    /** A sync outcome to record. */
    public record SyncBody(String outcome, String errorMessage) {
    }

    /** A decision on a draft. */
    public record ApproveBody(String finalSubject, String finalBodyText, String finalBodyHtml) {
    }

    /** What the provider reported. */
    public record EventBody(String eventType, String providerEventId, String details) {
    }

    /** A draft to create. */
    public record DraftBody(UUID caseId, String channel, String subject, String bodyText, String bodyHtml,
                            String fromAddress, List<String> toAddresses, List<String> ccAddresses,
                            UUID inReplyToMailId, String idempotencyKey) {
    }

    // ---------------------------------------------------------------- mailboxes

    /**
     * Connects a mailbox.
     *
     * <p>The password goes in and is never returned: the answer says the mailbox was registered, and nothing
     * about how to get into it.</p>
     */
    @PostMapping("/accounts")
    @PreAuthorize(CONNECT)
    @Operation(summary = "Connects a mailbox; the password is stored and never returned")
    public ResponseEntity<Map<String, Object>> connect(@RequestBody ConnectBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body.emailAddress() == null || body.emailAddress().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Postfach braucht eine Adresse");
        }
        if (body.imapHost() == null || body.imapHost().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Postfach braucht einen IMAP-Server");
        }

        MailAccountService.Account account = accounts.register(new MailAccountService.Connection(
                tenantId, body.workspaceId(), body.displayName(), body.emailAddress(), body.provider(),
                body.imapHost(), body.imapPort(), body.imapUsername(), Boolean.TRUE.equals(body.imapUseSsl()),
                body.smtpHost(), body.smtpPort(), body.smtpUsername(), body.password(), actor));

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "accountId", account.accountId().toString(),
                "status", account.status(),
                "emailAddress", account.emailAddress(),
                "note", "Das Kennwort wird gespeichert und nie zurückgegeben."));
    }

    /** The mailboxes of this account, with how the last collection went; never a password. */
    @GetMapping("/accounts")
    @PreAuthorize("hasAnyAuthority('mail.view','mail.configure','mail.connect')")
    @Operation(summary = "The connected mailboxes and the state of their last collection")
    public java.util.List<Map<String, Object>> listAccounts() {
        return accounts.list(tenant());
    }

    /** Whether the mailbox may be synchronised now, so an interface can say so before trying. */
    @GetMapping("/accounts/{accountId}/may-sync")
    @PreAuthorize(SEE)
    @Operation(summary = "Whether this mailbox may be synchronised now")
    public Map<String, Object> maySync(@PathVariable UUID accountId) {
        UUID tenantId = tenant();
        return Map.of("accountId", accountId.toString(),
                "maySync", accounts.maySync(tenantId, accountId));
    }

    /**
     * Records what a synchronisation did.
     *
     * <p>A failed sync is recorded with its message rather than swallowed: a mailbox that stopped working
     * quietly is one nobody notices until a deadline passes.</p>
     */
    @PostMapping("/accounts/{accountId}/sync-results")
    @PreAuthorize("hasAnyAuthority('mail.configure','mail.connect')")
    @Operation(summary = "Records the outcome of a synchronisation, including a failure")
    public ResponseEntity<Void> recordSync(@PathVariable UUID accountId, @RequestBody SyncBody body) {
        UUID tenantId = tenant();
        if (body == null || body.outcome() == null || body.outcome().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Ergebnis ist erforderlich");
        }
        accounts.recordSync(tenantId, accountId, body.outcome(), body.errorMessage());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- outgoing

    /**
     * Writes a draft.
     *
     * <p>Idempotent by the caller's key, so a retry after a timeout does not post a second letter.</p>
     */
    @GetMapping("/messages")
    @PreAuthorize(SEE)
    @Operation(summary = "Received, sent and draft mail in one list, newest first")
    public org.springframework.data.domain.Page<Map<String, Object>> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            List<de.buerokratiecopilot.mail.MailListService.Folder> folder,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            List<de.buerokratiecopilot.mail.MailListService.Status> status,
            @org.springframework.data.web.PageableDefault(size = 25) org.springframework.data.domain.Pageable pageable) {
        return messages.list(tenant(), q, folder, status, pageable);
    }

    @GetMapping("/messages/{messageId}")
    @PreAuthorize(SEE)
    @Operation(summary = "One received message for reading (plain text only)")
    public Map<String, Object> read(@PathVariable UUID messageId) {
        return messages.detail(tenant(), messageId);
    }

    @PostMapping("/messages")
    @PreAuthorize(SEND)
    @Operation(summary = "Writes a draft; the same key returns the draft that exists")
    public ResponseEntity<Map<String, Object>> draft(@RequestBody DraftBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body == null || body.subject() == null || body.subject().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Schreiben braucht einen Betreff");
        }
        if (body.toAddresses() == null || body.toAddresses().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Schreiben braucht einen Empfänger");
        }

        UUID messageId = outbound.draft(new OutboundMessageService.DraftRequest(tenantId, body.caseId(),
                body.channel(), body.subject(), body.bodyText(), body.bodyHtml(), body.fromAddress(),
                body.toAddresses(), body.ccAddresses(), body.inReplyToMailId(), body.idempotencyKey(),
                actor));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("messageId", messageId.toString(),
                "status", "DRAFT"));
    }

    /** Whether a message answers something that came in, which decides how it is filed. */
    @GetMapping("/messages/{messageId}/is-reply")
    @PreAuthorize(SEE)
    @Operation(summary = "Whether this message answers something that came in")
    public Map<String, Object> isReply(@PathVariable UUID messageId) {
        UUID tenantId = tenant();
        return Map.of("messageId", messageId.toString(),
                "isReplyToIncoming", outbound.isReplyToIncoming(tenantId, messageId));
    }

    /**
     * Approves a draft.
     *
     * <p><strong>The approver comes from the session and never from the body.</strong> An approval that named
     * itself would not be one: the whole point of the step is that a second person decided, and a caller who
     * could write that person's identifier could write any name they liked.</p>
     */
    @PostMapping("/messages/{messageId}/approve")
    @PreAuthorize(SEND)
    @Operation(summary = "Approves a draft; the approver comes from the session")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable UUID messageId,
                                                       @RequestBody(required = false) ApproveBody body) {
        UUID tenantId = tenant();
        UUID approver = CurrentUser.required();
        outbound.approve(tenantId, messageId, approver,
                body == null ? null : body.finalSubject(),
                body == null ? null : body.finalBodyText(),
                body == null ? null : body.finalBodyHtml());
        return ResponseEntity.ok(Map.of("messageId", messageId.toString(), "approvedBy",
                approver.toString(),
                "note", "Freigegeben; das Schreiben kann jetzt übergeben werden."));
    }

    /**
     * Records what the provider reported.
     *
     * <p>A status is only advanced by an event that proves it, which the delivery rules decide and not this
     * endpoint: a provider saying "queued" has delivered nothing, and an endpoint that treated the two alike
     * would show a letter as arrived before it left.</p>
     */
    @PostMapping("/messages/{messageId}/events")
    @PreAuthorize(SEND)
    @Operation(summary = "Records what the provider reported; only a proving event advances the status")
    public Map<String, Object> recordEvent(@PathVariable UUID messageId, @RequestBody EventBody body) {
        UUID tenantId = tenant();
        if (body == null || body.eventType() == null || body.eventType().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Ereignisart ist erforderlich");
        }
        DeliverySemantics.EventType type;
        try {
            type = DeliverySemantics.EventType.valueOf(
                    body.eventType().toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Unbekannte Ereignisart: " + body.eventType());
        }
        return Map.of("messageId", messageId.toString(),
                "status", outbound.recordEvent(tenantId, messageId, type, body.providerEventId(),
                        body.details()).name());
    }

    /**
     * Hands a message over to the provider.
     *
     * <p>The answer carries the provider's own status and identifier, so that "handed over" is never shown as
     * "delivered": those are different facts and only one of them means the letter arrived.</p>
     */
    @PostMapping("/messages/{messageId}/send")
    @PreAuthorize(SEND)
    @Operation(summary = "Hands a message over; the status is the provider's, not an assumption")
    public Map<String, Object> send(@PathVariable UUID messageId) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        OutboundMessageService.Handover handover = outbound.send(tenantId, messageId, actor);
        return Map.of("messageId", handover.messageId().toString(),
                "status", handover.status().name(),
                "providerMessageId", handover.providerMessageId() == null
                        ? "" : handover.providerMessageId(),
                "description", handover.description());
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}

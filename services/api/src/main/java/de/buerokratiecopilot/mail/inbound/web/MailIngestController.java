package de.buerokratiecopilot.mail.inbound.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.mail.inbound.ImapMailIngester;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Collecting mail from a connected mailbox (spec §12).
 *
 * <p>{@code ImapMailIngester} was implemented and tested with no endpoint, so a mailbox could be connected
 * and nothing would ever be collected from it.</p>
 *
 * <p><strong>A mailbox that was checked and had nothing is not the same as one that could not be
 * checked.</strong> A collection that answered "nothing new" because the server was unreachable would leave
 * somebody believing their post had been read, and a deadline letter sitting in a mailbox nobody actually
 * looked at is exactly the failure this distinction prevents. The run therefore reports what it collected and
 * <strong>fails loudly when it collected nothing because it could not</strong>.</p>
 *
 * <p><strong>A mailbox that is not connected is refused, not reported as empty.</strong> The service already
 * answers a conflict with that reason, and the route passes it through rather than turning it into a
 * successful run of zero.</p>
 *
 * <p><strong>How many were looked at is reported as well as how many were new.</strong> A run that found
 * nothing because it examined nothing is a different fact from one that examined fifty and found none, and a
 * caller who cannot tell them apart cannot tell a quiet week from a broken configuration.</p>
 *
 * <p>The permission is {@code mail.view} to read and {@code mail.configure} to run a collection: pulling mail
 * in is a change to the account's state, and it is the same permission that lets somebody change how the
 * mailbox is connected.</p>
 */
@RestController
@RequestMapping("/api/v1/mail/accounts")
@Tag(name = "Mail collection", description = "Collecting mail from a connected mailbox")
public class MailIngestController {

    private static final String COLLECT = "hasAnyAuthority('mail.configure','mail.connect')";

    private final ImapMailIngester ingester;

    public MailIngestController(ImapMailIngester ingester) {
        this.ingester = ingester;
    }

    /** How many messages to look at, which a caller may cap. */
    public record CollectBody(Integer limit) {
    }

    /**
     * Collects mail from a mailbox.
     *
     * <p>Reports what was examined and what was new, and fails rather than reporting an empty run when the
     * mailbox could not be reached.</p>
     */
    @PostMapping("/{accountId}/collect")
    @PreAuthorize(COLLECT)
    @Operation(summary = "Collects mail; reports what was examined and fails if the mailbox was unreachable")
    public ResponseEntity<Map<String, Object>> collect(@PathVariable UUID accountId,
                                                       @RequestBody(required = false) CollectBody body) {
        UUID tenantId = tenant();
        CurrentUser.required();
        int limit = body == null || body.limit() == null ? 50 : body.limit();
        if (limit <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es muss nach mindestens einer Nachricht "
                    + "gesehen werden");
        }

        ImapMailIngester.SyncResult result = ingester.ingest(tenantId, accountId, limit);
        Map<String, Object> answer = new LinkedHashMap<>(result.asDiagnostics());
        answer.put("accountId", accountId.toString());
        // Said in words, because 'nothing new' and 'nothing examined' are different facts and a caller who
        // cannot tell them apart cannot tell a quiet week from a broken configuration.
        answer.put("note", result.seen() == 0
                ? "Es wurde keine Nachricht gefunden. Ob das Postfach leer war oder nichts abgerufen werden "
                        + "konnte, sagt das Ergebnis oben."
                : "Das Postfach wurde abgerufen und die Nachrichten oben verarbeitet.");
        return ResponseEntity.status(HttpStatus.OK).body(answer);
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}

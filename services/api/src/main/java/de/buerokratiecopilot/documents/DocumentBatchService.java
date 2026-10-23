package de.buerokratiecopilot.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.documents.domain.BelegStatus;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Bulk work on the Posteingang (Kanzlei edition, §5.1).
 *
 * <p>The queue is a work list, not a form: a bookkeeper selects the morning's vouchers and moves them
 * together. The point of this service is what it <strong>does not</strong> do — it does not write a status
 * with an {@code update} statement. Every voucher goes through {@link DocumentService#changeBelegStatus} or
 * {@link DocumentService#assignMandant}, the same rules the single-row buttons use. A selection is a
 * convenience, never a second, looser path through the status machine: a voucher that may not move is
 * reported with the reason the rule gave, and the office sees why rather than a silent success.</p>
 *
 * <p>Each voucher is its own transaction. A batch is a list of independent decisions; one refused voucher
 * must not roll back the nine that were valid, and the audit entry of a change that happened must not
 * disappear because a later one failed.</p>
 */
@Service
public class DocumentBatchService {

    /** The highest number of vouchers accepted in one call: a mistaken "select all" must not lock the office. */
    public static final int MAX_IDS = 500;

    /** What the office asked to do with the selected vouchers. */
    public enum Action {
        /** "Annehmen": NEU → OFFEN. */
        ACCEPT(BelegStatus.OFFEN),
        /** "Zur Prüfung": the voucher needs a person (OFFEN → ZU_PRUEFEN). */
        TO_CHECK(BelegStatus.ZU_PRUEFEN),
        /** "Als Fehler markieren": from any state that may fail. */
        MARK_ERROR(BelegStatus.FEHLER),
        /** "Mandant zuordnen": the client comes with the request. */
        ASSIGN_MANDANT(null);

        private final BelegStatus target;

        Action(BelegStatus target) {
            this.target = target;
        }
    }

    public record Skipped(UUID id, String reason) {
    }

    public record Result(int updated, List<Skipped> skipped) {
    }

    /** What one voucher did: changed, or stayed with the office's reason. */
    private record Outcome(int changed, String reason) {
        static final Outcome CHANGED = new Outcome(1, null);

        static Outcome unchanged(String reason) {
            return new Outcome(0, reason);
        }
    }

    private final DocumentService documents;
    private final JdbcTemplate jdbc;

    public DocumentBatchService(DocumentService documents, JdbcTemplate jdbc) {
        this.documents = documents;
        this.jdbc = jdbc;
    }

    /**
     * Applies one action to every selected voucher and reports what was left alone.
     *
     * @param ids       the selection; 1…{@value #MAX_IDS} after duplicates are removed
     * @param action    the action; null is refused, not guessed
     * @param mandantId the client for {@link Action#ASSIGN_MANDANT}, ignored otherwise
     */
    public Result apply(List<UUID> ids, Action action, UUID mandantId) {
        if (action == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurde keine Aktion angegeben.");
        }
        if (ids != null && ids.size() > MAX_IDS) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Es lassen sich höchstens " + MAX_IDS + " Belege auf einmal bearbeiten.");
        }
        // A repeated identifier in one request is one voucher, worked on once: the second occurrence would only
        // turn into a confusing "already in this state" note.
        List<UUID> selection = ids == null ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (selection.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurde kein Beleg ausgewählt.");
        }
        if (action == Action.ASSIGN_MANDANT) {
            requireMandant(mandantId);
        }

        int updated = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (UUID id : selection) {
            Outcome outcome;
            try {
                outcome = action == Action.ASSIGN_MANDANT ? assign(id, mandantId) : move(id, action.target);
            }
            catch (ApiException refused) {
                // Everything the rules refuse becomes a line in the answer, never a silent skip.
                outcome = Outcome.unchanged(reasonOf(refused));
            }
            updated += outcome.changed();
            if (outcome.reason() != null) {
                skipped.add(new Skipped(id, outcome.reason()));
            }
        }
        return new Result(updated, List.copyOf(skipped));
    }

    /**
     * Moves one voucher through the status machine, or explains why it stayed where it was.
     *
     * <p>The rule runs for every selected voucher, including one that already stands in the target state:
     * the batch has no second opinion about what a voucher may do. The comparison of the two states is what
     * distinguishes "moved" from "was already there" — {@code changeBelegStatus} deliberately treats a
     * repeated confirmation as success and writes no audit entry for it.</p>
     */
    private Outcome move(UUID id, BelegStatus target) {
        // The value is captured, not the entity: within one request the persistence context may hand the same
        // managed instance to both calls, and an object that is updated in place would make every move look
        // like "no change".
        BelegStatus previous = documents.require(id).getBelegStatus();
        Document after = documents.changeBelegStatus(id, target);
        if (after.getBelegStatus() == previous) {
            return Outcome.unchanged("Der Beleg steht bereits auf " + label(target) + ".");
        }
        return Outcome.CHANGED;
    }

    /** Assigns one voucher through the same path as the card, so the audit wording stays in one place. */
    private Outcome assign(UUID id, UUID mandantId) {
        UUID previous = documents.require(id).getMandantId();
        Document after = documents.assignMandant(id, mandantId);
        if (Objects.equals(previous, after.getMandantId())) {
            return Outcome.unchanged("Der Beleg ist bereits diesem Mandanten zugeordnet.");
        }
        return Outcome.CHANGED;
    }

    /**
     * A mandant must be a contact of this account. The foreign key alone would accept a contact of another
     * account, because PostgreSQL checks the target table without applying its row-level policy — the same
     * guard the manual endpoint uses (§5.2).
     */
    private void requireMandant(UUID mandantId) {
        if (mandantId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Für die Zuordnung fehlt der Mandant.");
        }
        UUID tenantId = RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
        Integer exists = jdbc.queryForObject("""
                select count(*) from contacts
                 where id = ? and tenant_id = ? and deleted_at is null
                """, Integer.class, mandantId, tenantId);
        if (exists == null || exists == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Mandant.");
        }
    }

    /** The office's words for a refused voucher, never a stack-trace or an English system message. */
    private static String reasonOf(ApiException refused) {
        if (refused.errorCode() == ErrorCode.NOT_FOUND) {
            return "Beleg nicht gefunden.";
        }
        String message = refused.getMessage();
        return message == null || message.isBlank() ? "Der Beleg konnte nicht geändert werden." : message;
    }

    /** The status as the screen names it (§7: Neu, Offen, Zu prüfen, Exportiert, Fehler). */
    static String label(BelegStatus status) {
        return switch (status) {
            case NEU -> "Neu";
            case OFFEN -> "Offen";
            case ZU_PRUEFEN -> "Zu prüfen";
            case EXPORTIERT -> "Exportiert";
            case FEHLER -> "Fehler";
        };
    }
}

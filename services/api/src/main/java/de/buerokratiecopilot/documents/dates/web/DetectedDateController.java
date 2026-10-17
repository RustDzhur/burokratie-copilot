package de.buerokratiecopilot.documents.dates.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.documents.dates.DetectedDateService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * A date found in a document, offered for the calendar (spec §13.2).
 *
 * <p><strong>The gap this closes.</strong> A person photographed a letter with a deadline on it, uploaded it,
 * and no calendar entry appeared — although the calendar had been promised to fill itself. The reason was
 * not OCR: the platform computes deadlines from a register of legal rules, that register ships empty by
 * design, and "the date written on the paper" is not something a rule computes. So the date was read and
 * thrown away.</p>
 *
 * <p><strong>A date printed on a letter needs no rule.</strong> Offering it makes no claim about the law:
 * the platform says where it found the date, shows the sentence, and a person decides whether it is a
 * deadline. That is the whole point of the offer — and the reason it is an offer rather than an automatic
 * entry.</p>
 *
 * <p><strong>The sentence is served with the date, always.</strong> A date without its source cannot be
 * checked, and a wrong date in a calendar is worse than an empty one.</p>
 *
 * <p>The permissions are the catalogue's own: {@code documents.view} to read, and the same authorities the
 * calendar uses to write, because this is a calendar entry created from a document.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Detected dates", description = "Dates found in a document, offered for the calendar")
public class DetectedDateController {

    private static final String SEE = "hasAnyAuthority('documents.view','documents.review')";
    
    private final DetectedDateService dates;

    public DetectedDateController(DetectedDateService dates) {
        this.dates = dates;
    }

    /** What a caller sends to put one of the dates in the calendar. */
    public record AddBody(String date, String title, String snippet) {
    }

    /**
     * Dates found in the document, each with its page and the sentence it appears in.
     *
     * <p>An empty list means the document has no date the platform could read — not that it has no deadline.
     * The note says so, because those are different statements and the second one matters to the reader.</p>
     */
    @GetMapping("/{documentId}/detected-dates")
    @PreAuthorize(SEE)
    @Operation(summary = "Dates found in a document, with the sentence each came from")
    public Map<String, Object> detected(@PathVariable UUID documentId) {
        List<DetectedDateService.Detected> found = dates.detect(documentId);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("dates", found.stream().map(DetectedDateService.Detected::asMap).toList());
        answer.put("count", found.size());
        // The distinction that has caused real confusion: no date read is not the same as no deadline.
        answer.put("note", found.isEmpty()
                ? "In diesem Dokument wurde kein Datum gelesen. Das heißt nicht, dass es keine Frist hat."
                : "Diese Daten stehen im Dokument. Ob eines davon eine Frist ist, entscheiden Sie.");
        return answer;
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenantOf() {
        return RequestContext.tenantId().orElseThrow();
    }
}

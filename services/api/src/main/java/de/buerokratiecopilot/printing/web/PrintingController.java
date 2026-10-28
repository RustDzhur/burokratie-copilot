package de.buerokratiecopilot.printing.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.printing.PrintService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Printing: letters, registers and labels (spec §27).
 *
 * <p>{@code PrintService} was implemented and tested with no endpoint, so nothing the platform produced
 * could be put on paper.</p>
 *
 * <p><strong>The permission is {@code documents.export}, because a PDF is a document leaving the
 * platform.</strong> That is what the step is, and naming it that way is what lets an account decide who may
 * take documents out rather than who may press a print button.</p>
 *
 * <p><strong>The file name is built from the title, which is user input, and so it is sanitised.</strong> A
 * title going into a {@code Content-Disposition} header unexamined is how a newline in a title becomes a
 * header of the sender's choosing; the name is reduced to letters, digits, dot, dash and underscore before
 * it is used, and a title that reduces to nothing falls back to a fixed name rather than an empty one.</p>
 *
 * <p><strong>A label run reports how many labels came back.</strong> Printing ten labels and receiving eight
 * is the failure that matters, and it is invisible if the answer is only a list — the count requested and
 * the count produced are both in the response so that a short run is a fact rather than something somebody
 * notices at the printer.</p>
 *
 * <p>Nothing here invents content: the lines and rows are the caller's, and the platform lays them out. A
 * register that filled in figures of its own would be producing an accounting document out of nothing.</p>
 */
@RestController
@RequestMapping("/api/v1/printing")
@Tag(name = "Printing", description = "Letters, registers and labels as PDF")
public class PrintingController {

    private static final String EXPORT = "hasAuthority('documents.export')";

    private final PrintService printing;

    public PrintingController(PrintService printing) {
        this.printing = printing;
    }

    /** A letter or notice to print. */
    public record LetterBody(String title, String subjectType, UUID subjectId, List<String> lines,
                             String footer) {
    }

    /** A register: several entries under one heading. */
    public record RegisterBody(String title, String subjectType, UUID subjectId,
                               List<String> columnHeaders, List<List<String>> rows, String footer) {
    }

    /** A run of labels. */
    public record LabelsBody(List<LetterBody> labels) {
    }

    /**
     * Renders a letter as a PDF.
     *
     * <p>The disposition is an attachment rather than inline: a PDF rendered in a browser tab is a document
     * somebody can screenshot out of a session they have walked away from.</p>
     */
    @PostMapping("/documents")
    @PreAuthorize(EXPORT)
    @Operation(summary = "Renders a letter as a downloadable PDF")
    public ResponseEntity<byte[]> letter(@RequestBody LetterBody body) {
        RequestContext.tenantId().orElseThrow();
        CurrentUser.required();
        requireTitle(body == null ? null : body.title());
        requireLines(body == null ? null : body.lines());

        PrintService.Printed printed = printing.print(new PrintService.PrintRequest(body.title(),
                body.subjectType(), body.subjectId(), body.lines(), body.footer()));
        return pdf(printed, body.title());
    }

    /** Renders a register with its headings and rows as a PDF. */
    @PostMapping("/registers")
    @PreAuthorize(EXPORT)
    @Operation(summary = "Renders a register as a downloadable PDF")
    public ResponseEntity<byte[]> register(@RequestBody RegisterBody body) {
        RequestContext.tenantId().orElseThrow();
        CurrentUser.required();
        requireTitle(body == null ? null : body.title());
        if (body == null || body.columnHeaders() == null || body.columnHeaders().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Register braucht Spaltenüberschriften");
        }

        PrintService.Printed printed = printing.printRegister(new PrintService.RegisterRequest(
                body.title(), body.subjectType(), body.subjectId(), body.columnHeaders(), body.rows(),
                body.footer()));
        return pdf(printed, body.title());
    }

    /**
     * Renders a run of labels.
     *
     * <p>Returns what came back rather than the bytes: a caller printing ten labels and receiving eight has a
     * problem at the printer, and the counts in the answer are what makes that visible instead of leaving it
     * to be discovered.</p>
     */
    @PostMapping("/labels")
    @PreAuthorize(EXPORT)
    @Operation(summary = "Renders a run of labels and reports how many came back")
    public Map<String, Object> labels(@RequestBody LabelsBody body) {
        RequestContext.tenantId().orElseThrow();
        CurrentUser.required();
        if (body == null || body.labels() == null || body.labels().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurden keine Etiketten angegeben");
        }
        for (LetterBody label : body.labels()) {
            requireTitle(label.title());
        }

        List<PrintService.Printed> printed = printing.printLabels(body.labels().stream()
                .map(label -> new PrintService.PrintRequest(label.title(), label.subjectType(),
                        label.subjectId(), label.lines(), label.footer()))
                .toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", body.labels().size());
        result.put("produced", printed.size());
        result.put("references", printed.stream().map(PrintService.Printed::reference).toList());
        result.put("pages", printed.stream().mapToInt(PrintService.Printed::pages).sum());
        // Said plainly when the run is short: a caller who asked for ten and got eight has to know from the
        // answer, not from the printer.
        result.put("complete", printed.size() == body.labels().size());
        result.put("note", printed.size() == body.labels().size()
                ? "Alle Etiketten wurden erzeugt."
                : "Es wurden weniger Etiketten erzeugt als angefordert.");
        return result;
    }

    /** A PDF attachment, with a name built from the title. */
    private static ResponseEntity<byte[]> pdf(PrintService.Printed printed, String title) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(safeFileName(title) + ".pdf", java.nio.charset.StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(printed.pdf().length);
        return new ResponseEntity<>(printed.pdf(), headers, HttpStatus.OK);
    }

    /**
     * A file name that cannot become a header of the sender's choosing.
     *
     * <p>The title is user input: a newline in it, left alone, would end the header and start another. Only
     * letters, digits, dot, dash and underscore survive, and a title that reduces to nothing becomes a fixed
     * name rather than an empty one.</p>
     */
    static String safeFileName(String title) {
        String reduced = title == null ? "" : title.replaceAll("[^A-Za-z0-9._-]", "_");
        if (reduced.isBlank() || reduced.chars().allMatch(c -> c == '_')) {
            return "druck";
        }
        return reduced.length() > 80 ? reduced.substring(0, 80) : reduced;
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Ausdruck braucht eine Überschrift");
        }
    }

    private static void requireLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Ausdruck ohne Zeilen ist leer");
        }
    }
}

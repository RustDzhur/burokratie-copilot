package de.buerokratiecopilot.templates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.documents.content.RichDocument;
import de.buerokratiecopilot.documents.export.DocumentExporter;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Producing a document and keeping it, in one act (spec §6 and §15, E-004).
 *
 * <p>The exporter and the register existed separately, which meant a caller could produce a letter and not
 * record it — and a letter that was produced and not recorded is a letter nobody can prove was sent. The
 * two are therefore done together: what comes out of the export is what goes into the register, in one
 * call, so there is no way to have one without the other.</p>
 *
 * <p><strong>The stored body is the text as it was rendered.</strong> The blocks are the source, but the
 * register keeps the plain text, because the question asked of a recorded letter is what it said, not
 * which block types it was made of. The format is recorded next to it, so a letter that went out as a PDF
 * is not later mistaken for one that went out as text.</p>
 *
 * <p><strong>A document with unfilled fields is still produced</strong> — somebody has to be able to see
 * what is missing in order to fill it in — but it is recorded as blocked, and the refusal to call it
 * finished happens in the register rather than here. Printing a draft is not the same as sending one.</p>
 */
@Service
public class GeneratedDocumentExportService {

    private static final Logger LOG = LoggerFactory.getLogger(GeneratedDocumentExportService.class);

    private final DocumentExporter exporter;
    private final GeneratedDocumentService register;

    public GeneratedDocumentExportService(DocumentExporter exporter, GeneratedDocumentService register) {
        this.exporter = exporter;
        this.register = register;
    }

    /** What a caller supplies. */
    public record Request(RichDocument.Document document, UUID templateId, UUID templateVersionId,
                          UUID caseId, String subjectType, UUID subjectId, String format,
                          Map<String, Object> fieldValues, List<String> unresolvedFields) {
    }

    /** The produced file and the record of it. */
    public record Produced(byte[] content, String filename, String mimeType,
                           GeneratedDocumentService.Stored record) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filename", filename);
            map.put("mimeType", mimeType);
            map.put("bytes", content.length);
            map.put("recordId", record.id().toString());
            map.put("status", record.status());
            map.put("unresolvedFields", record.unresolvedFields());
            map.put("ready", record.isReady());
            return map;
        }
    }

    /**
     * Renders a document and records it.
     *
     * <p>Both happen or neither does: a produced letter that was not recorded is a letter nobody can prove
     * was sent, and a record of a letter that was never produced is worse.</p>
     */
    @Transactional
    public Produced produce(UUID tenantId, Request request, UUID producedBy) {
        if (request == null || request.document() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurde kein Dokument übergeben");
        }
        String format = request.format() == null ? "PDF"
                : request.format().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("PDF", "DOCX", "TXT", "HTML").contains(format)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekanntes Ausgabeformat: "
                    + request.format());
        }

        DocumentExporter.Exported exported = switch (format) {
            case "PDF" -> exporter.toPdf(request.document());
            case "DOCX" -> exporter.toDocx(request.document());
            // The schema allows HTML and TXT, so they are produced rather than refused: a caller that asks
            // for text has a reason, and saying "unsupported" for a format the platform already stores
            // would be an odd refusal.
            case "HTML" -> new DocumentExporter.Exported(toHtml(request.document()).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8), "text/html",
                    filename(request.document(), "html"), Map.of("blocks", request.document().blocks().size()));
            default -> new DocumentExporter.Exported(toText(request.document()).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8), "text/plain",
                    filename(request.document(), "txt"), Map.of("blocks", request.document().blocks().size()));
        };

        GeneratedDocumentService.Stored stored = register.record(tenantId,
                new GeneratedDocumentService.Request(request.templateId(), request.templateVersionId(),
                        request.caseId(), request.subjectType(), request.subjectId(),
                        request.document().title() == null || request.document().title().isBlank()
                                ? exported.filename() : request.document().title(),
                        format, textOf(request.document()), request.fieldValues(),
                        request.unresolvedFields()),
                producedBy);

        LOG.info("Produced {} ({}, {} bytes) and recorded it as {}", exported.filename(), format,
                exported.content().length, stored.id());
        return new Produced(exported.content(), exported.filename(), exported.mimeType(), stored);
    }

    /**
     * The text of a document, which is what the register keeps.
     *
     * <p>The question asked of a recorded letter is what it said, not which block types it was made of, so
     * the plain text is what is stored.</p>
     */
    static String textOf(RichDocument.Document document) {
        StringBuilder text = new StringBuilder();
        for (RichDocument.Block block : document.blocks()) {
            switch (block) {
                case RichDocument.Block.Heading heading -> text.append(heading.text()).append('\n');
                case RichDocument.Block.Paragraph paragraph -> text.append(paragraph.text()).append('\n');
                case RichDocument.Block.ListItem item -> text.append(item.ordered() ? "1. " : "- ")
                        .append(item.text()).append('\n');
                case RichDocument.Block.Table table -> {
                    if (!table.headers().isEmpty()) {
                        text.append(String.join(" | ", table.headers())).append('\n');
                    }
                    for (List<String> row : table.rows()) {
                        text.append(String.join(" | ", row)).append('\n');
                    }
                }
                case RichDocument.Block.Image image -> text.append("[Bild, ")
                        .append(image.data() == null ? 0 : image.data().length).append(" Bytes]\n");
                case RichDocument.Block.PageBreak ignored -> text.append('\n');
            }
        }
        return text.toString();
    }

    /** The document as HTML, for a caller that asked for a format the schema also stores. */
    static String toHtml(RichDocument.Document document) {
        StringBuilder html = new StringBuilder("<article>");
        if (document.title() != null && !document.title().isBlank()) {
            html.append("<h1>").append(escape(document.title())).append("</h1>");
        }
        for (RichDocument.Block block : document.blocks()) {
            switch (block) {
                case RichDocument.Block.Heading heading -> html.append("<h").append(heading.level())
                        .append('>').append(escape(heading.text())).append("</h").append(heading.level())
                        .append('>');
                case RichDocument.Block.Paragraph paragraph -> html.append("<p>")
                        .append(escape(paragraph.text())).append("</p>");
                case RichDocument.Block.ListItem item -> html.append("<li>")
                        .append(escape(item.text())).append("</li>");
                case RichDocument.Block.Table table -> {
                    html.append("<table>");
                    for (List<String> row : table.rows()) {
                        html.append("<tr>");
                        for (String cell : row) {
                            html.append("<td>").append(escape(cell)).append("</td>");
                        }
                        html.append("</tr>");
                    }
                    html.append("</table>");
                }
                case RichDocument.Block.Image ignored -> html.append("<p>[Bild]</p>");
                case RichDocument.Block.PageBreak ignored -> html.append("<hr/>");
            }
        }
        return html.append("</article>").toString();
    }

    /** The text of a document, as a caller that asked for TXT would want it. */
    static String toText(RichDocument.Document document) {
        return textOf(document);
    }

    private static String filename(RichDocument.Document document, String extension) {
        String base = document.reference() != null && !document.reference().isBlank()
                ? document.reference()
                : document.title() != null && !document.title().isBlank() ? document.title() : "dokument";
        return base.replaceAll("[^A-Za-z0-9._-]", "_") + "." + extension;
    }

    /** Escaped, so that a letter containing an angle bracket does not become markup. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** All the formats a stored document can be produced in. */
    public static List<String> formats() {
        List<String> formats = new ArrayList<>(List.of("PDF", "DOCX", "HTML", "TXT"));
        return List.copyOf(formats);
    }
}

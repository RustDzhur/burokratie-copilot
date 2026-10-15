package de.buerokratiecopilot.documents.merge;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.InMemoryUpload;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Pages that were uploaded one by one but belong together (nine photos of one form) become one document: a PDF whose pages are the
 * photos in order, received like any upload, so it is read, sorted and turned into work as one document. The single pages are then
 * deleted the ordinary way (their open tasks go with them), so nothing is listed twice.
 *
 * <p>The platform proposes which uploaded pages belong together (photos uploaded by one person in one go, numbered one after
 * another), but only a person decides: a proposal is merged only when asked for.</p>
 */
@Service
public class DocumentMergeService {

    private static final Logger log = LoggerFactory.getLogger(DocumentMergeService.class);

    /** Photos uploaded by the same person with less than this between two of them are one go. */
    static final Duration SAME_GO = Duration.ofMinutes(20);
    static final int MAX_PAGES = 60;
    /** A photo is reduced to this long side before it goes into the PDF, so that a phone's twelve megapixels do not make a 40 MB file. */
    private static final int LONG_SIDE_MAX = 2400;
    private static final Pattern NUMBERED = Pattern.compile("^(.*?)(\\d+)(\\.[A-Za-z0-9]+)?$");

    public record Group(List<UUID> documentIds, List<String> filenames, String suggestedTitle) {
    }

    private final JdbcTemplate jdbc;
    private final DocumentService documents;
    private final AuditService audit;

    public DocumentMergeService(JdbcTemplate jdbc, DocumentService documents, AuditService audit) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.audit = audit;
    }

    /** Groups of uploaded photos that probably are the pages of one document. */
    public List<Group> candidates() {
        UUID tenant = RequestContext.tenantId().orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, original_filename, created_by, received_at from documents
                where tenant_id = ? and deleted_at is null and mime_type like 'image/%'
                  and source in ('UPLOAD', 'MOBILE', 'SCANNER') and legal_hold = false
                  and duplicate_of_document_id is null and created_by is not null
                order by created_by, received_at
                """, tenant);
        List<List<Map<String, Object>>> runs = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!current.isEmpty()) {
                Map<String, Object> last = current.getLast();
                boolean sameGo = last.get("created_by").equals(row.get("created_by"))
                        && Duration.between(((Timestamp) last.get("received_at")).toInstant(),
                        ((Timestamp) row.get("received_at")).toInstant()).compareTo(SAME_GO) <= 0;
                if (!sameGo) {
                    runs.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(row);
        }
        if (!current.isEmpty()) {
            runs.add(current);
        }
        List<Group> groups = new ArrayList<>();
        for (List<Map<String, Object>> run : runs) {
            if (run.size() < 2) {
                continue;
            }
            List<Map<String, Object>> ordered = orderByNumber(run);
            for (int from = 0; from < ordered.size(); from += MAX_PAGES) {
                List<Map<String, Object>> part = ordered.subList(from, Math.min(ordered.size(), from + MAX_PAGES));
                if (part.size() < 2) {
                    continue;
                }
                Instant first = ((Timestamp) run.getFirst().get("received_at")).toInstant();
                groups.add(new Group(part.stream().map(r -> (UUID) r.get("id")).toList(),
                        part.stream().map(r -> (String) r.get("original_filename")).toList(),
                        "Scan " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm").withZone(ZoneId.of("Europe/Berlin")).format(first) + ".pdf"));
            }
        }
        return groups;
    }

    /** By the number in the file name when every name has one with the same beginning (IMG_9304 before IMG_9305), otherwise as uploaded. */
    static List<Map<String, Object>> orderByNumber(List<Map<String, Object>> run) {
        String prefix = null;
        Map<Map<String, Object>, Long> numbers = new LinkedHashMap<>();
        for (Map<String, Object> row : run) {
            Matcher m = NUMBERED.matcher(String.valueOf(row.get("original_filename")));
            if (!m.matches() || (prefix != null && !prefix.equals(m.group(1)))) {
                return run;
            }
            prefix = m.group(1);
            numbers.put(row, Long.parseLong(m.group(2)));
        }
        List<Map<String, Object>> sorted = new ArrayList<>(run);
        sorted.sort(Comparator.comparing(numbers::get));
        return sorted;
    }

    /** Makes one PDF of the given documents, in the given order, and deletes the single ones. Returns the new document. */
    public Document merge(List<UUID> ids, String title) {
        if (ids == null || ids.size() < 2) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Zum Zusammenführen sind mindestens zwei Dokumente nötig");
        }
        if (ids.size() > MAX_PAGES || ids.stream().distinct().count() != ids.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Höchstens " + MAX_PAGES + " verschiedene Dokumente");
        }
        byte[] pdf = buildPdf(ids);
        String name = title == null || title.isBlank() ? "Zusammengeführt.pdf" : title.strip();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            name = name + ".pdf";
        }
        var received = documents.receive(new DocumentService.UploadRequest(
                new InMemoryUpload(name, "application/pdf", pdf), null, null, Document.Source.UPLOAD, null));
        // The merged document exists; now the single pages go, with the work that was made from each of them.
        for (UUID id : ids) {
            documents.delete(id, "Zu einem Dokument zusammengeführt");
        }
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.DOCUMENT, "DOCUMENTS_MERGED")
                .tenant(received.document().getTenantId())
                .entity("documents", received.document().getId(), name)
                .description(ids.size() + " Dokumente zu einem zusammengeführt")
                .changes(Map.of("sources", ids.stream().map(UUID::toString).toList()))
                .build());
        log.info("Merged {} documents into {}", ids.size(), received.document().getId());
        return received.document();
    }

    private byte[] buildPdf(List<UUID> ids) {
        List<PDDocument> sources = new ArrayList<>();
        try (PDDocument pdf = new PDDocument()) {
            for (UUID id : ids) {
                var download = documents.openContent(id);
                byte[] bytes;
                try (var in = download.content()) {
                    bytes = in.readAllBytes();
                }
                String type = download.contentType() == null ? "" : download.contentType().toLowerCase(java.util.Locale.ROOT);
                if (type.startsWith("application/pdf")) {
                    PDDocument source = Loader.loadPDF(bytes);
                    sources.add(source);
                    for (PDPage page : source.getPages()) {
                        pdf.importPage(page);
                    }
                }
                else if (type.startsWith("image/")) {
                    addImagePage(pdf, bytes, download.filename());
                }
                else {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "Nur Fotos und PDF-Dateien lassen sich zusammenführen: " + download.filename());
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "Das PDF konnte nicht erstellt werden: " + ex.getMessage());
        }
        finally {
            for (PDDocument source : sources) {
                try {
                    source.close();
                }
                catch (IOException ignored) {
                    // Nothing more to do with a source that cannot be closed.
                }
            }
        }
    }

    private void addImagePage(PDDocument pdf, byte[] bytes, String filename) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new ApiException(ErrorCode.FILE_REJECTED, "Das Bild kann nicht gelesen werden: " + filename);
        }
        image = ExifOrientation.upright(image, ExifOrientation.of(bytes));
        int longSide = Math.max(image.getWidth(), image.getHeight());
        if (longSide > LONG_SIDE_MAX) {
            double scale = (double) LONG_SIDE_MAX / longSide;
            BufferedImage small = new BufferedImage((int) Math.round(image.getWidth() * scale),
                    (int) Math.round(image.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
            var g = small.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, small.getWidth(), small.getHeight(), null);
            g.dispose();
            image = small;
        }
        else if (image.getType() != BufferedImage.TYPE_INT_RGB && image.getColorModel().hasAlpha()) {
            BufferedImage flat = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = flat.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
            image = flat;
        }
        // The page is as wide as an A4 page (or as tall when the photo lies on its side) and as long as the picture is.
        boolean landscape = image.getWidth() > image.getHeight();
        float width = landscape ? PDRectangle.A4.getHeight() : PDRectangle.A4.getWidth();
        float height = width * image.getHeight() / image.getWidth();
        PDPage page = new PDPage(new PDRectangle(width, height));
        pdf.addPage(page);
        PDImageXObject object = JPEGFactory.createFromImage(pdf, image, 0.82f);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.drawImage(object, 0, 0, width, height);
        }
    }
}

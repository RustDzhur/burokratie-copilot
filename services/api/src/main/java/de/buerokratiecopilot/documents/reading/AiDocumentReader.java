package de.buerokratiecopilot.documents.reading;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.ai.AiOrchestrator;
import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.documents.ocr.PdfPageRenderer;
import de.buerokratiecopilot.orchestration.CaseOrchestrator;

/**
 * Reads a document with a model that looks at the page, for what OCR cannot read: handwriting, stamps, forms filled in by
 * hand.
 *
 * <p>It runs only when a person presses the button for this document, never by itself, because the pages leave the
 * platform for the configured AI provider. It refuses unless external AI is switched on for the platform and allowed for
 * the tenant, and it stays inside the monthly budget (both are checked by {@link AiOrchestrator} before anything is
 * sent). The pictures are not stored anywhere by the platform and not written to any log; the job record says how many
 * pages there were.</p>
 *
 * <p>What comes back is a proposal, like every other reading: the values are stored as unverified fields (extractor
 * {@code AI}), a value that a person has already confirmed is never replaced, and a value the model could not read is
 * {@code null}, never a guess. The platform does not change the document type, pay, answer or sign on the strength of
 * it.</p>
 */
@Service
public class AiDocumentReader {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentReader.class);
    private static final int MAX_PAGES = 3;
    private static final int MAX_SIDE = 1800;

    private static final String INSTRUCTION = """
            Du liest ein Dokument anhand der beigefügten Seitenbilder. Es kann gedruckt oder handschriftlich sein, \
            deutsch, englisch, russisch oder ukrainisch.
            Antworte AUSSCHLIESSLICH mit einem JSON-Objekt, ohne weiteren Text, mit diesen Feldern:
            {"documentDate": "YYYY-MM-DD oder null", "periodFrom": "YYYY-MM-DD oder null", "periodTo": "YYYY-MM-DD oder null", \
            "deadline": "YYYY-MM-DD oder null", "amount": Zahl oder null, "currency": "EUR oder null", \
            "reference": "Aktenzeichen / Rechnungsnummer oder null", "sender": "Absender oder null", \
            "handwritten": true oder false, "summary": "ein bis zwei Sätze, was das Dokument ist und was es verlangt", \
            "unsure": ["Felder, die schwer lesbar waren"]}
            Regeln: Erfinde nichts. Was nicht zu lesen ist oder nicht im Dokument steht, ist null. "documentDate" ist das \
            Datum, das auf dem Dokument selbst steht (Ausstellungs-, Rechnungs-, Unterschriftsdatum). "deadline" nur, wenn \
            das Dokument ein Datum nennt, bis zu dem etwas geschehen muss; rechne keine Frist aus. Deutsche Daten wie \
            22.10.25 sind Tag.Monat.Jahr.
            """;

    private final AiOrchestrator ai;
    private final DocumentService documents;
    private final PdfPageRenderer renderer;
    private final DocumentExtractionRepository extractions;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CaseOrchestrator orchestrator;
    private final ObjectMapper json;
    private final de.buerokratiecopilot.shared.config.BurokratieProperties properties;

    public AiDocumentReader(AiOrchestrator ai, DocumentService documents, PdfPageRenderer renderer,
                            DocumentExtractionRepository extractions, JdbcTemplate jdbc, AuditService audit,
                            CaseOrchestrator orchestrator, ObjectMapper json,
                            de.buerokratiecopilot.shared.config.BurokratieProperties properties) {
        this.ai = ai;
        this.documents = documents;
        this.renderer = renderer;
        this.extractions = extractions;
        this.jdbc = jdbc;
        this.audit = audit;
        this.orchestrator = orchestrator;
        this.json = json;
        this.properties = properties;
    }

    /** What the reading did, in codes the screen turns into sentences. */
    public record Result(String status, Map<String, String> found, String summary, boolean handwritten) {

        static Result of(String status) {
            return new Result(status, Map.of(), null, false);
        }
    }

    @Transactional
    public Result read(UUID documentId, UUID userId) {
        return read(documentId, userId, "MANUAL", null);
    }

    /** Types whose pages are never sent to an external AI on the platform's own initiative: health data and the like. */
    private static final java.util.Set<String> NEVER_AUTOMATIC = java.util.Set.of("medizinische_rechnung",
            "medizinisches_dokument", "krankmeldung");

    /**
     * Reads the document by AI if, and only if, the platform's own reading has left a value unread that it needs.
     *
     * <p>The order is the point: label search and OCR come first and cost nothing. The AI is the last resort, used when
     * (a) the document type says a date matters (a deadline, the period a form covers), (b) the document names it (its
     * label is in the text) but no readable value follows (a blank or a handwritten entry), or the scan itself is poor,
     * and (c) nothing of this kind has been tried for the document before. Documents of health topics or marked
     * confidential never go out on their own, a workspace has a daily ceiling, and the monthly budget still applies. A
     * person's click on the button is the way around all of this, for one document.</p>
     *
     * @return the result when the AI was asked, or {@code null} when the platform's own reading was enough
     */
    @Transactional
    public Result readIfNeeded(UUID documentId) {
        if (!properties.getAi().isEnabled() || !properties.getAi().isAutoRead() || !ai.hasAvailableProvider()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.tenant_id, d.mime_type, d.confidentiality, d.document_type_id, t.code as type_code,
                       coalesce(d.extracted_text, '') as text,
                       s.due_field_keys, s.creates_task, s.creates_contract
                from documents d
                join document_types t on t.id = d.document_type_id
                join document_type_scenarios s on s.document_type_code = t.code and s.tenant_id is null
                where d.id = ? and d.deleted_at is null and d.processing_status = 'READY'
                """, documentId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String mime = String.valueOf(row.get("mime_type")).toLowerCase();
        String typeCode = String.valueOf(row.get("type_code"));
        String confidentiality = String.valueOf(row.get("confidentiality"));
        boolean picture = mime.equals("application/pdf") || mime.startsWith("image/");
        boolean matters = Boolean.TRUE.equals(row.get("creates_task")) || Boolean.TRUE.equals(row.get("creates_contract"));
        if (!picture || !matters || NEVER_AUTOMATIC.contains(typeCode)
                || confidentiality.equals("CONFIDENTIAL") || confidentiality.equals("STRICT")) {
            return null;
        }
        UUID tenantId = (UUID) row.get("tenant_id");

        // Tried before (even if it failed or was refused): not again on every reprocessing.
        Integer before = jdbc.queryForObject("""
                select count(*) from ai_jobs where tenant_id = ? and job_type = 'DOCUMENT_READ'
                  and input::text like ?
                """, Integer.class, tenantId, "%" + documentId + "%");
        if (before != null && before > 0) {
            return null;
        }
        Integer today = jdbc.queryForObject("""
                select count(*) from ai_jobs where tenant_id = ? and job_type = 'DOCUMENT_READ'
                  and requested_by is null and created_at >= date_trunc('day', now())
                """, Integer.class, tenantId);
        if (today != null && today >= properties.getAi().getAutoReadDailyLimit()) {
            log.info("Tenant {} reached its daily limit of automatic AI readings", tenantId);
            return null;
        }

        Set<String> needed = new java.util.LinkedHashSet<>();
        String dueKeys = row.get("due_field_keys") == null ? "" : String.valueOf(row.get("due_field_keys"));
        for (String key : dueKeys.split(",")) {
            if (!key.isBlank()) {
                needed.add(key.strip());
            }
        }
        needed.add("Zeitraum");
        Set<String> extracted = new java.util.HashSet<>(jdbc.queryForList(
                "select field_key from document_extractions where document_id = ?", String.class, documentId));
        Map<String, List<String>> labels = new LinkedHashMap<>();
        jdbc.query("""
                select field_key, extraction_hint from document_type_fields
                where document_type_id = ? and extraction_hint is not null
                """, rs -> {
            labels.put(rs.getString("field_key"), List.of(rs.getString("extraction_hint").split("\\|")));
        }, row.get("document_type_id"));
        Double ocr = jdbc.queryForObject(
                "select avg(ocr_confidence)::float8 from document_pages where document_id = ? and ocr_used",
                Double.class, documentId);

        String reason = evidenceOfUnreadValue(needed, labels, extracted, String.valueOf(row.get("text")), ocr);
        if (reason == null) {
            return null;
        }
        log.info("Document {} goes to AI reading: {}", documentId, reason);
        return read(documentId, null, "AUTO", reason);
    }

    /**
     * Why the platform's own reading was not enough, or {@code null} if it was.
     *
     * <p>A needed value is unread when its label stands in the text and no value was taken from it: a blank line or
     * handwriting that OCR cannot turn into a date. A poor scan (mean OCR confidence below 0.6) with a needed value
     * missing is the second sign. A document that simply does not mention a deadline is not a reason: absence is an
     * answer, not a gap.</p>
     */
    static String evidenceOfUnreadValue(Set<String> neededKeys, Map<String, List<String>> labelsByKey,
                                        Set<String> extractedKeys, String text, Double meanOcrConfidence) {
        String lower = text == null ? "" : text.toLowerCase(java.util.Locale.GERMAN);
        boolean anyMissing = false;
        for (String key : neededKeys) {
            if (extractedKeys.contains(key)) {
                continue;
            }
            anyMissing = true;
            for (String label : labelsByKey.getOrDefault(key, List.of())) {
                String needle = label.strip().toLowerCase(java.util.Locale.GERMAN);
                if (needle.isEmpty()) {
                    continue;
                }
                if (java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(needle)
                        + "(?![\\p{L}\\p{N}])").matcher(lower).find()) {
                    return "label \"" + label.strip() + "\" is in the text but no readable value follows (" + key + ")";
                }
            }
        }
        if (anyMissing && meanOcrConfidence != null && meanOcrConfidence < 0.6) {
            return "the scan is poorly readable (mean OCR confidence " + Math.round(meanOcrConfidence * 100)
                    + " %) and a needed value is missing";
        }
        return null;
    }

    @Transactional
    public Result read(UUID documentId, UUID userId, String trigger, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.tenant_id, d.original_filename, d.mime_type,
                       (select v.id from document_versions v where v.document_id = d.id
                         order by v.version desc limit 1) as version_id
                from documents d where d.id = ? and d.deleted_at is null
                """, documentId);
        if (rows.isEmpty()) {
            return Result.of("NOT_FOUND");
        }
        UUID tenantId = (UUID) rows.get(0).get("tenant_id");
        UUID versionId = (UUID) rows.get(0).get("version_id");
        String filename = String.valueOf(rows.get(0).get("original_filename"));
        String mime = String.valueOf(rows.get(0).get("mime_type")).toLowerCase();

        if (!mime.equals("application/pdf") && !mime.equals("image/png") && !mime.equals("image/jpeg")
                && !mime.equals("image/gif") && !mime.equals("image/webp")) {
            return Result.of("UNSUPPORTED_TYPE");
        }
        List<String> pictures;
        try (var content = documents.openContent(documentId).content()) {
            pictures = pictures(content.readAllBytes(), mime);
        }
        catch (IOException ex) {
            log.warn("Document {} could not be prepared for reading: {}", documentId, ex.getMessage());
            return Result.of("UNREADABLE");
        }
        if (pictures.isEmpty()) {
            return Result.of("UNREADABLE");
        }

        AiOrchestrator.Outcome outcome = ai.run(tenantId, "DOCUMENT_READ", INSTRUCTION,
                "document " + documentId + " (" + pictures.size() + " page image(s))", null, "doc-read-v1",
                sha256(INSTRUCTION), userId, pictures);
        if (!"SUCCEEDED".equals(outcome.status())) {
            // SKIPPED_DISABLED, SKIPPED_BUDGET or FAILED: the screen says which, and nothing was invented.
            return Result.of(outcome.status());
        }

        Parsed parsed;
        try {
            parsed = parse(outcome.text());
        }
        catch (IOException | RuntimeException ex) {
            log.warn("The answer for document {} was not usable JSON", documentId);
            return Result.of("UNUSABLE_ANSWER");
        }

        Map<String, String> found = store(documentId, versionId, parsed);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.DOCUMENT, "DOCUMENT_READ_BY_AI")
                .tenant(tenantId).entity("documents", documentId, filename)
                .description("Pages were sent to the AI provider " + outcome.provider()
                        + ("AUTO".equals(trigger) ? " because the platform's own reading left a needed value unread ("
                        + reason + ")" : " at the request of a person") + "; " + found.size() + " value(s) proposed")
                .changes(Map.of("provider", String.valueOf(outcome.provider()), "pages", pictures.size(),
                        "fields", String.join(",", found.keySet()), "trigger", trigger,
                        "reason", reason == null ? "" : reason))
                .build());

        // A date that was handwritten may change what the document is: re-evaluate it, unless a task already exists.
        Integer tasks = jdbc.queryForObject("""
                select count(*) from tasks where source_document_id = ? and source = 'DOCUMENT' and deleted_at is null
                """, Integer.class, documentId);
        if (!"AUTO".equals(trigger) && tasks != null && tasks == 0 && !found.isEmpty()) {
            orchestrator.orchestrate(documentId);
        }
        return new Result("OK", found, parsed.summary(), parsed.handwritten());
    }

    /** What the model said, after validation. */
    record Parsed(LocalDate documentDate, LocalDate periodFrom, LocalDate periodTo, LocalDate deadline,
                  BigDecimal amount, String reference, String sender, String summary, boolean handwritten) {
    }

    Parsed parse(String text) throws IOException {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("no JSON object in the answer");
        }
        JsonNode node = json.readTree(text.substring(start, end + 1));
        return new Parsed(date(node, "documentDate"), date(node, "periodFrom"), date(node, "periodTo"),
                date(node, "deadline"), amount(node.path("amount")), string(node, "reference"),
                string(node, "sender"), string(node, "summary"), node.path("handwritten").asBoolean(false));
    }

    private static LocalDate date(JsonNode node, String key) {
        String value = string(node, key);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        }
        catch (RuntimeException ex) {
            // A date the model got wrong in form is not repaired by guessing what it meant.
            return null;
        }
    }

    private static BigDecimal amount(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().replace(",", ".").replaceAll("[^0-9.\\-]", ""));
            }
            catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static String string(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").strip();
        return text.isEmpty() || text.equalsIgnoreCase("null") ? null : text;
    }

    /** Stores the proposals as unverified fields; confirmed values stay as they are. */
    private Map<String, String> store(UUID documentId, UUID versionId, Parsed parsed) {
        Map<String, String[]> values = new LinkedHashMap<>(); // key -> {valueType, value, normalized}
        if (parsed.documentDate() != null) {
            values.put("Schreibendatum", new String[] {"DATE", parsed.documentDate().toString(),
                    parsed.documentDate().toString()});
        }
        if (parsed.periodFrom() != null && parsed.periodTo() != null) {
            values.put("Zeitraum", new String[] {"PERIOD", parsed.periodFrom() + " bis " + parsed.periodTo(),
                    parsed.periodFrom() + "/" + parsed.periodTo()});
        }
        if (parsed.deadline() != null) {
            values.put("Frist", new String[] {"DATE", parsed.deadline().toString(), parsed.deadline().toString()});
        }
        if (parsed.amount() != null) {
            values.put("Betrag", new String[] {"AMOUNT", parsed.amount().toPlainString(),
                    parsed.amount().toPlainString()});
        }
        if (parsed.reference() != null) {
            values.put("Aktenzeichen", new String[] {"REFERENCE", parsed.reference(), parsed.reference()});
        }

        Map<String, String> stored = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : values.entrySet()) {
            DocumentExtraction existing = extractions.findByDocumentIdAndFieldKey(documentId, entry.getKey())
                    .orElse(null);
            if (existing != null && existing.getVerifiedBy() != null) {
                continue; // a person has confirmed it
            }
            DocumentExtraction row = existing != null ? existing : new DocumentExtraction(UUID.randomUUID(),
                    documentId, versionId, entry.getKey(), entry.getValue()[0], "AI", "OCR");
            row.setFieldValue(entry.getValue()[1]);
            row.setNormalizedValue(entry.getValue()[2]);
            row.setConfidence(new BigDecimal("0.6000"));
            row.setSourceSnippet("Mit KI aus dem Bild gelesen (ungeprüft)");
            extractions.save(row);
            stored.put(entry.getKey(), entry.getValue()[1]);
        }
        return stored;
    }

    /** Up to three pages as reduced JPEG data URLs: sharp enough to read handwriting, small enough to send. */
    private List<String> pictures(byte[] bytes, String mime) throws IOException {
        List<byte[]> pages = new ArrayList<>();
        if (mime.equals("application/pdf")) {
            int count = Math.min(MAX_PAGES, renderer.pageCount(bytes));
            for (int page = 1; page <= count; page++) {
                pages.add(renderer.renderPage(bytes, page));
            }
        }
        else {
            pages.add(bytes);
        }
        List<String> pictures = new ArrayList<>();
        for (byte[] page : pages) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(page));
            if (image == null) {
                continue;
            }
            pictures.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg(reduce(image))));
        }
        return pictures;
    }

    private static BufferedImage reduce(BufferedImage image) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        double factor = longest > MAX_SIDE ? (double) MAX_SIDE / longest : 1.0;
        int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = out.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return out;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

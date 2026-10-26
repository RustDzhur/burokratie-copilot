package de.buerokratiecopilot.agents.documents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentVersionRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.documents.fields.FieldExtractionService;
import de.buerokratiecopilot.documents.fields.FieldValueParser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * {@code doc-extract} — Daten-Extraktion.
 *
 * <p>Reads the number, dates, amounts and parties of a document with fixed rules, no external AI:
 * the declared fields of the document type are extracted by {@link FieldExtractionService}, and the
 * document's own number, date, sender and recipient are filled from the page text when they are not
 * known yet. Filling only empty fields makes the agent idempotent: a re-run changes nothing that a
 * person already corrected.</p>
 */
@Component
public class DocExtractAgent implements Agent {

    /** Labels whose line starts a document/reference number. */
    static final List<String> NUMBER_LABELS = List.of(
            "rechnungsnummer", "rechnungs-nr", "rechnungsnr", "rechnungs nr",
            "vorgangsnummer", "aktenzeichen", "belegnummer", "geschäftszeichen", "geschaeftszeichen");

    /** Labels whose line names a party. */
    static final List<String> SENDER_LABELS = List.of("absender", "von");
    static final List<String> RECIPIENT_LABELS = List.of("an:", "empfänger", "empfaenger", "adressat");

    /** A date in German or ISO notation anywhere in the text. */
    private static final Pattern DATE_ANYWHERE = Pattern.compile(
            "\\b(\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}|\\d{4}-\\d{2}-\\d{2})\\b");

    /** An amount in German or English notation, optionally with a currency symbol. */
    private static final Pattern AMOUNT_ANYWHERE = Pattern.compile(
            "[-+]?\\d{1,3}(?:[.]\\d{3})*(?:,\\d{2})?\\s*(?:€|EUR)", Pattern.CASE_INSENSITIVE);

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentPageRepository pages;
    private final FieldExtractionService fieldExtraction;

    public DocExtractAgent(DocumentRepository documents, DocumentVersionRepository versions,
                           DocumentPageRepository pages, FieldExtractionService fieldExtraction) {
        this.documents = documents;
        this.versions = versions;
        this.pages = pages;
        this.fieldExtraction = fieldExtraction;
    }

    @Override
    public String id() {
        return "doc-extract";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID documentId = parseUuid(context.input("documentId"));
        if (documentId == null) {
            return AgentResult.skipped(
                    "Keine documentId angegeben; ohne Dokument gibt es nichts zu extrahieren.");
        }
        return RequestContext.runAs(context.tenantId(), () -> extract(context, documentId));
    }

    private AgentResult extract(AgentContext context, UUID documentId) {
        Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, context.tenantId())
                .orElse(null);
        if (document == null) {
            return AgentResult.failed(
                    "Dokument " + documentId + " wurde nicht gefunden (oder gehört einem anderen Tenant).");
        }
        DocumentVersion version = versions.findFirstByDocumentIdOrderByVersionDesc(document.getId())
                .orElse(null);
        if (version == null) {
            return AgentResult.failed("Das Dokument hat keine Version; es gibt keinen Text zu extrahieren.");
        }

        List<DocumentPage> documentPages = pages.findByVersionIdOrderByPageNumberAsc(version.getId());
        String text = joinedText(documentPages, document.getExtractedText());
        if (text.isBlank()) {
            return AgentResult.skipped(
                    "Das Dokument trägt keinen Text: die Seiten sind leer und es gibt keinen "
                            + "extrahierten Text.");
        }

        List<AgentResult.Produced> produced = new ArrayList<>();
        Map<String, String> meta = new LinkedHashMap<>();
        int extracted = 0;
        int fieldsNotFound = 0;

        // 1. The declared fields of the document type, through the existing rule engine. Re-running
        //    is safe: verified values are kept and stale unverified rows are replaced, never doubled.
        if (!documentPages.isEmpty()) {
            FieldExtractionService.Result result = fieldExtraction.extract(document, version);
            extracted = result.found().size();
            fieldsNotFound = result.notFound().size();
            for (FieldExtractionService.Found found : result.found()) {
                produced.add(new AgentResult.Produced("field-value",
                        document.getId().toString(), found.fieldKey() + "=" + found.value()));
            }
            meta.put("fieldsExtracted", String.valueOf(extracted));
            meta.put("fieldsNotFound", String.valueOf(fieldsNotFound));
        }

        // 2. The document's own metadata, only where it is still empty — a human's entry is never
        //    overwritten by a rule.
        boolean changed = false;
        if (isBlank(document.getDocumentNumber())) {
            String number = findNumber(text);
            if (number != null) {
                document.setDocumentNumber(number);
                produced.add(new AgentResult.Produced("document-number",
                        document.getId().toString(), number));
                changed = true;
            }
        }
        if (document.getDocumentDate() == null) {
            LocalDate date = findDate(text);
            if (date != null) {
                document.setDocumentDate(date);
                produced.add(new AgentResult.Produced("document-date",
                        document.getId().toString(), date.toString()));
                changed = true;
            }
        }
        if (isBlank(document.getSender())) {
            String sender = findParty(text, SENDER_LABELS);
            if (sender != null) {
                document.setSender(sender);
                produced.add(new AgentResult.Produced("document-sender",
                        document.getId().toString(), sender));
                changed = true;
            }
        }
        if (isBlank(document.getRecipient())) {
            String recipient = findParty(text, RECIPIENT_LABELS);
            if (recipient != null) {
                document.setRecipient(recipient);
                produced.add(new AgentResult.Produced("document-recipient",
                        document.getId().toString(), recipient));
                changed = true;
            }
        }
        if (changed) {
            documents.save(document);
        }

        // 3. Amounts are reported for the caller; the values themselves live in the field
        //    extraction above, which is the only writer of extraction rows.
        BigDecimal amount = findAmount(text);
        if (amount != null) {
            meta.put("detectedAmount", amount.toPlainString());
        }

        if (produced.isEmpty() && !text.isBlank() && extracted == 0) {
            return AgentResult.skipped(
                    "Keine Felder erkannt: im Text fanden sich weder Nummer, Datum, Betrag noch Parteien.");
        }
        if (produced.isEmpty()) {
            return AgentResult.skipped(
                    "Nichts Neues extrahiert: Nummer, Datum und Parteien sind bereits gesetzt.");
        }

        meta.put("documentNumber", document.getDocumentNumber() == null ? ""
                : document.getDocumentNumber());
        meta.put("documentDate", document.getDocumentDate() == null ? ""
                : document.getDocumentDate().toString());
        meta.put("sender", document.getSender() == null ? "" : document.getSender());
        return AgentResult.done(
                "Extrahiert: " + produced.size() + " Feld(er)"
                        + (fieldsNotFound > 0 ? ", " + fieldsNotFound + " deklarierte Feld(er) nicht gefunden" : ""),
                produced, meta);
    }

    private static String joinedText(List<DocumentPage> documentPages, String extractedText) {
        StringBuilder text = new StringBuilder();
        for (DocumentPage page : documentPages) {
            if (page.getTextContent() != null) {
                text.append(page.getTextContent()).append('\n');
            }
        }
        if (text.isEmpty() && extractedText != null) {
            text.append(extractedText);
        }
        return text.toString();
    }

    /** The value behind the first matching label; the label must stand at a token boundary. */
    static String findValueAfterLabel(String text, List<String> labels) {
        String lowered = text.toLowerCase(Locale.GERMAN);
        for (String label : labels) {
            int from = 0;
            while (true) {
                int index = lowered.indexOf(label, from);
                if (index < 0) {
                    break;
                }
                int after = index + label.length();
                boolean boundaryBefore = index == 0
                        || !Character.isLetterOrDigit(lowered.charAt(index - 1));
                boolean boundaryAfter = after >= lowered.length()
                        || !Character.isLetterOrDigit(lowered.charAt(after));
                if (boundaryBefore && boundaryAfter) {
                    int lineEnd = lowered.indexOf('\n', after);
                    String rest = text.substring(after, lineEnd < 0 ? text.length() : lineEnd);
                    String value = rest.replaceFirst("^\\s*[:\\-–—.]?\\s*", "").strip();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
                from = index + 1;
            }
        }
        return null;
    }

    /** A number from the line behind a number label; only plausible references are accepted. */
    static String findNumber(String text) {
        String value = findValueAfterLabel(text, NUMBER_LABELS);
        if (value == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-./]{2,40}").matcher(value);
        if (matcher.find()) {
            String candidate = matcher.group();
            // A pure date is a date, not a number.
            if (!candidate.matches("\\d{1,2}[.]\\d{1,2}[.]\\d{2,4}")) {
                return candidate;
            }
        }
        return null;
    }

    /** The first date in the text, as a real calendar date; nothing is guessed. */
    static LocalDate findDate(String text) {
        Matcher matcher = DATE_ANYWHERE.matcher(text);
        while (matcher.find()) {
            FieldValueParser.Parsed parsed = FieldValueParser.parse(matcher.group(1), "DATE");
            if (parsed.valid()) {
                try {
                    return LocalDate.parse(parsed.normalized());
                }
                catch (java.time.DateTimeException ex) {
                    // not a real date; keep looking
                }
            }
        }
        return null;
    }

    /** The party behind a party label; the name may stand after the label or on the next line. */
    static String findParty(String text, List<String> labels) {
        String value = findValueAfterLabel(text, labels);
        // The value may continue on the next line ("An:\nMax Mustermann") or start on it.
        String firstLine = value == null ? null : value.split("\\R", 2)[0].strip();
        if (firstLine == null || firstLine.isEmpty()) {
            String lowered = text.toLowerCase(Locale.GERMAN);
            for (String label : labels) {
                int index = lowered.indexOf(label);
                if (index >= 0) {
                    int lineEnd = lowered.indexOf('\n', index);
                    if (lineEnd >= 0) {
                        String next = text.substring(lineEnd + 1, text.length())
                                .split("\\R", 2)[0].strip();
                        if (!next.isEmpty() && next.length() <= 200) {
                            return next;
                        }
                    }
                }
            }
            return null;
        }
        String trimmed = firstLine.strip();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    /** The first amount in the text; German and English thousands separators are both read. */
    static BigDecimal findAmount(String text) {
        Matcher matcher = AMOUNT_ANYWHERE.matcher(text);
        while (matcher.find()) {
            FieldValueParser.Parsed parsed = FieldValueParser.parse(matcher.group(), "AMOUNT");
            if (parsed.valid()) {
                try {
                    return new BigDecimal(parsed.normalized());
                }
                catch (NumberFormatException ex) {
                    // keep looking
                }
            }
        }
        return null;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

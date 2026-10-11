package de.buerokratiecopilot.documents.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.springframework.stereotype.Component;

/**
 * Extracts the content of an e-mail file (spec §6.1, §7.2).
 *
 * <p>Headers are included because they are the evidence: who sent what, when, and with which
 * reference. Attachments are not decoded here — they are separate documents — but their names are
 * listed so a reviewer can see whether something is missing.</p>
 */
@Component
public class EmlTextExtractor implements TextExtractor {

    /** Only the headers that matter for administrative correspondence. */
    private static final List<String> HEADERS = List.of("From", "To", "Cc", "Reply-To", "Date",
            "Subject", "Message-ID", "In-Reply-To", "References", "X-Aktenzeichen");

    @Override
    public boolean supports(String contentType) {
        return "message/rfc822".equals(contentType);
    }

    @Override
    public String format() {
        return "eml";
    }

    @Override
    public ExtractedText extract(InputStream content, long sizeBytes) throws IOException {
        try {
            Session session = Session.getInstance(new Properties());
            MimeMessage message = new MimeMessage(session, content);

            StringBuilder text = new StringBuilder();
            Map<String, Object> diagnostics = new LinkedHashMap<>();

            for (String header : HEADERS) {
                String[] values = message.getHeader(header);
                if (values != null && values.length > 0) {
                    text.append(header).append(": ").append(String.join(", ", values)).append('\n');
                    diagnostics.put(header.toLowerCase(java.util.Locale.ROOT), String.join(", ", values));
                }
            }
            text.append('\n');

            List<String> attachmentNames = new ArrayList<>();
            appendPart(message, text, attachmentNames);

            diagnostics.put("attachments", attachmentNames);
            diagnostics.put("sizeBytes", sizeBytes);

            return ExtractedText.singleSection(text.toString().strip(), ExtractedText.Method.TEXT_LAYER,
                    diagnostics);
        }
        catch (MessagingException ex) {
            throw new IOException("The e-mail file could not be parsed: " + ex.getMessage(), ex);
        }
    }

    /** Walks the MIME tree; text parts are appended, attachments are only recorded by name. */
    private static void appendPart(Part part, StringBuilder text, List<String> attachmentNames)
            throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            text.append(String.valueOf(part.getContent())).append('\n');
            return;
        }
        if (part.isMimeType("text/html")) {
            // The plain text alternative is preferred; if only HTML exists it is reduced to text
            // without interpreting markup, because running a renderer over untrusted mail is not
            // something a text extraction step should do.
            text.append(stripMarkup(String.valueOf(part.getContent()))).append('\n');
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                appendPart(multipart.getBodyPart(i), text, attachmentNames);
            }
            return;
        }
        String fileName = part.getFileName();
        if (fileName != null && !fileName.isBlank()) {
            attachmentNames.add(fileName);
        }
    }

    /** Minimal, non-executing reduction of HTML to text. */
    static String stripMarkup(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}

package de.buerokratiecopilot.mail.inbound;

import java.util.List;

import de.buerokratiecopilot.ai.security.InjectionDetector;
import de.buerokratiecopilot.documents.classification.RuleBasedClassifier;

/**
 * Sorts an incoming mail into one of the categories of the inbox (spec 7.2) from what the mail itself says.
 *
 * <p>Deterministic and cheap: words of the subject, the beginning of the text, the sender and the names of the attachments. A
 * mail that looks like a trick (an instruction aimed at an AI, a request to "confirm your account") is marked suspicious first,
 * because every other label would hide it. The category only labels the mail; the attachments are still processed as documents,
 * and nothing is deleted or hidden because of a label. The confidence is a rough strength of the evidence, not a probability.</p>
 */
public final class MailCategoriser {

    /** The result: category, strength of the evidence, and whether the mail is suspicious. */
    public record Result(String category, double confidence, boolean suspicious) {
    }

    private static final List<String> PHISHING = List.of("konto gesperrt", "konto wurde gesperrt", "bestaetigen sie ihre daten",
            "passwort bestaetigen", "zugangsdaten eingeben", "verify your account", "your account has been suspended",
            "sofort handeln", "ihr konto wird geschlossen", "tan eingeben");
    private static final List<String> ADVERTISEMENT = List.of("newsletter", "abbestellen", "abmelden unter", "unsubscribe",
            "nur noch heute", "jetzt sparen", "rabatt", "gutschein code", "sonderaktion");
    private static final List<String> INVOICE = List.of("rechnung", "zahlungserinnerung", "mahnung", "invoice", "zahlungsaufforderung",
            "abschlag", "beitragsrechnung");
    private static final List<String> CONTRACT = List.of("vertrag", "kuendigung", "vertragsbestaetigung", "auftragsbestaetigung",
            "vertragsaenderung", "preisanpassung", "verlaengerung");
    private static final List<String> NOTIFICATION = List.of("benachrichtigung", "bestaetigung", "erinnerung", "termin", "ihre sendung",
            "versandbestaetigung", "passwort zurueck", "sicherheitshinweis");

    private MailCategoriser() {
    }

    private static boolean has(String folded, List<String> words) {
        return words.stream().anyMatch(folded::contains);
    }

    public static Result classify(String subject, String bodyText, String from, List<String> attachmentNames, boolean isReply) {
        String head = RuleBasedClassifier.fold((subject == null ? "" : subject) + " "
                + (bodyText == null ? "" : bodyText.substring(0, Math.min(bodyText.length(), 1500))));
        String names = RuleBasedClassifier.fold(String.join(" ", attachmentNames == null ? List.of() : attachmentNames));
        boolean attachments = attachmentNames != null && !attachmentNames.isEmpty();

        if (InjectionDetector.containsAttempt(bodyText == null ? "" : bodyText) || has(head, PHISHING)) {
            return new Result("SUSPICIOUS", 0.9, true);
        }
        String subjectFolded = RuleBasedClassifier.fold(subject == null ? "" : subject);
        if (isReply || subjectFolded.matches("^(aw|re|antwort)\\s*:.*")) {
            return new Result("RESPONSES", 0.85, false);
        }
        if (has(head, INVOICE) || has(names, List.of("rechnung", "invoice", "mahnung"))) {
            return new Result("INVOICES", has(names, INVOICE) ? 0.9 : 0.7, false);
        }
        if (has(head, CONTRACT) || has(names, CONTRACT)) {
            return new Result("CONTRACTS", 0.7, false);
        }
        if (has(head, ADVERTISEMENT)) {
            return new Result("ADVERTISEMENT", 0.8, false);
        }
        if (has(head, NOTIFICATION)) {
            return new Result("NOTIFICATIONS", 0.6, false);
        }
        if (attachments && has(names, List.of("pdf", "jpg", "jpeg", "png", "tif", "docx"))) {
            return new Result("INCOMING_DOCUMENTS", 0.5, false);
        }
        return new Result("OTHER", 0.0, false);
    }
}

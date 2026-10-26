package de.buerokratiecopilot.agents.communication;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.ai.security.InjectionDetector;
import de.buerokratiecopilot.documents.classification.RuleBasedClassifier;

/**
 * {@code chat-assist} — Chat-Assistenz.
 *
 * <p>Answers typical customer requests with rule-based response blocks: the request is folded and
 * matched against a fixed catalogue of topics (billing, appointment, authorisation, address, data
 * protection, complaint, …), and the matching blocks plus a next-step suggestion are returned.
 *
 * <p>Deliberately deterministic and offline: no external AI, no network, no persistence. A request
 * that reads like an attempt to steer the assistant (prompt injection) is refused rather than
 * answered — the same guard the mail pipeline applies.</p>
 */
@Component
public class ChatAssistAgent implements Agent {

    /** One topic rule: the words that open it, the answer block and the suggested next step. */
    private record Rule(String topic, List<String> keywords, String block, String nextStep) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("rechnung", List.of("rechnung", "zahlung", "betrag", "überweisung", "mahnung"),
                    "Gerne prüfen wir Ihre Rechnung. Senden Sie uns die Rechnungsnummer und den "
                            + "betroffenen Betrag; wir klären den Sachverhalt und melden uns schriftlich.",
                    "Beleg als Dokument aufnehmen und dem Vorgang zuordnen."),
            new Rule("termin", List.of("termin", "gespräch", "besprechung", "rückruf", "anruf"),
                    "Wir vereinbaren gerne einen Termin. Nennen Sie uns zwei Wunschtermine; "
                            + "wir bestätigen einen davon verbindlich.",
                    "Termin im Kalender vormerken."),
            new Rule("vollmacht", List.of("vollmacht", "vertretung", "bevollmächtig"),
                    "Für die Vertretung benötigen wir eine schriftliche Vollmacht. Ein Formular "
                            + "stellen wir Ihnen auf Wunsch bereit.",
                    "Vorlage „Vollmacht“ über den Briefentwurf erzeugen."),
            new Rule("adresse", List.of("adresse", "umzug", "anschrift", "adressänderung"),
                    "Bitte teilen Sie uns Ihre neue Anschrift mit. Wir aktualisieren Ihre Daten und "
                            + "bestätigen die Änderung.",
                    "Kontaktdaten im CRM aktualisieren."),
            new Rule("datenschutz", List.of("datenschutz", "auskunft", "daten löschen", "löschung", "dsgvo"),
                    "Auskunfts- und Löschanfragen bearbeiten wir gemäß DSGVO. Ihre Anfrage wird "
                            + "dokumentiert und fristgerecht beantwortet.",
                    "Anfrage als Betroffenenanfrage erfassen."),
            new Rule("beschwerde", List.of("beschwerde", "unzufrieden", "reklamation", "problem"),
                    "Ihre Beschwerde nehmen wir ernst. Beschreiben Sie bitte kurz, was vorgefallen ist; "
                            + "wir prüfen den Vorgang und antworten Ihnen zeitnah.",
                    "Beschwerde im Vorgang protokollieren."),
            new Rule("frist", List.of("frist", "widerspruch", "einspruch", "bis wann"),
                    "Bitte nennen Sie uns das Schreiben mit der Frist. Wir prüfen die Frist und halten "
                            + "den Termin für Sie nach.",
                    "Frist über den Erinnerungs-Agenten erfassen."),
            new Rule("unterlagen", List.of("unterlagen", "dokument", "bescheid", "nachweis", "anlage"),
                    "Bitte senden Sie uns die Unterlagen als Anhang oder über das Portal. Nach Eingang "
                            + "bestätigen wir den Erhalt.",
                    "Eingehende Dokumente dem Vorgang zuordnen."));

    private static final int MAX_BLOCKS = 3;

    @Override
    public String id() {
        return "chat-assist";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String text = context.input("text");
        if (text == null || text.isBlank()) {
            return AgentResult.skipped("Keine Anfrage vorhanden: das Feld 'text' fehlt.");
        }

        if (InjectionDetector.containsAttempt(text)) {
            return AgentResult.failed(
                    "Die Anfrage enthält Anweisungen an das System und wird nicht beantwortet.");
        }

        String folded = RuleBasedClassifier.fold(text);
        List<Rule> matched = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.keywords().stream().anyMatch(folded::contains)) {
                matched.add(rule);
                if (matched.size() >= MAX_BLOCKS) {
                    break;
                }
            }
        }
        if (matched.isEmpty()) {
            return AgentResult.skipped(
                    "Keine passende Antwortregel gefunden. Das Anliegen bitte manuell beantworten.");
        }

        List<AgentResult.Produced> produced = new ArrayList<>();
        List<String> topics = new ArrayList<>();
        List<String> nextSteps = new ArrayList<>();
        for (Rule rule : matched) {
            produced.add(new AgentResult.Produced("response_block", rule.topic(), rule.block()));
            topics.add(rule.topic());
            if (!nextSteps.contains(rule.nextStep())) {
                nextSteps.add(rule.nextStep());
            }
        }

        Map<String, String> meta = new java.util.LinkedHashMap<>();
        meta.put("topics", String.join(",", topics));
        meta.put("blockCount", String.valueOf(matched.size()));
        meta.put("nextStep", String.join(" ", nextSteps));

        return AgentResult.done(
                "Antwort-Bausteine für " + String.join(", ", topics) + " zusammengestellt.",
                produced, meta);
    }
}

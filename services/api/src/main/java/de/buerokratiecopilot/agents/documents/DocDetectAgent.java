package de.buerokratiecopilot.agents.documents;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * {@code doc-detect} — Dokument-Erkennung.
 *
 * <p>Determines the document type from filename, MIME type and a text hint, using the rules
 * from the manifest. This is the front door of the document pipeline: every other document
 * agent is chosen by the type this one reports. Rule-based on purpose: no external AI in the
 * pilot, deterministic and explainable.</p>
 */
@Component
public class DocDetectAgent implements Agent {

    private static final Map<String, String> KEYWORD_TO_TYPE = Map.ofEntries(
            Map.entry("rechnung", "invoice"),
            Map.entry("invoice", "invoice"),
            Map.entry("mahnung", "dunning"),
            Map.entry("vertrag", "contract"),
            Map.entry("contract", "contract"),
            Map.entry("kündigung", "termination"),
            Map.entry("bescheid", "notice"),
            Map.entry("antrag", "application"),
            Map.entry("vollmacht", "authorization"),
            Map.entry("gehaltsabrechnung", "payslip"),
            Map.entry("versicherung", "insurance"),
            Map.entry("gutachten", "expertise"),
            Map.entry("rezept", "prescription"),
            Map.entry("zeugnis", "certificate"));

    @Override
    public String id() {
        return "doc-detect";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String fileName = context.input("fileName");
        String mimeType = context.input("mimeType");
        String textHint = context.input("textHint");

        String type = detect(fileName, mimeType, textHint);
        if (type == null) {
            return AgentResult.skipped(
                    "Dokumenttyp nicht erkannt: keine Regeln passen zu Dateiname, MIME-Typ oder Texthinweis.");
        }
        return AgentResult.done("Dokumenttyp erkannt: " + type,
                List.of(new AgentResult.Produced("classification", type,
                        "Erkannt als " + type)),
                Map.of("detectedType", type, "fileName", fileName == null ? "" : fileName,
                        "channel", channelFor(type)));
    }

    private String detect(String fileName, String mimeType, String textHint) {
        String haystack = ((fileName == null ? "" : fileName) + " "
                + (textHint == null ? "" : textHint)).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> rule : KEYWORD_TO_TYPE.entrySet()) {
            if (haystack.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        if ("application/pdf".equalsIgnoreCase(mimeType)) {
            return "document";
        }
        if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return "scan";
        }
        return null;
    }

    private String channelFor(String type) {
        return switch (type) {
            case "invoice", "dunning", "payslip", "insurance" -> "finance";
            case "contract", "termination", "authorization", "notice" -> "legal";
            default -> "documents";
        };
    }
}

package de.buerokratiecopilot.documents.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;

/**
 * Determines the language of a document (spec §9.1 step 7).
 *
 * <p>A small, transparent heuristic instead of a statistical model, and deliberately so: the value is
 * used to choose OCR language data and to select a template language, and a wrong but confident guess
 * would be worse than none. The detector therefore reports a language only when its evidence is
 * clearly above the alternatives, and leaves the field empty when it is not — "unknown" is a usable
 * answer, a wrong language is not.</p>
 *
 * <p>The signals are German, English and Russian function words plus language-specific characters
 * (umlauts, ß, Cyrillic), which is exactly the vocabulary this platform receives.</p>
 */
@Component
public class LanguageDetectStepHandler implements ProcessingStepHandler {

    private static final List<String> GERMAN = List.of(" der ", " die ", " das ", " und ", " nicht ",
            " sie ", " mit ", " für ", " auf ", " ist ", " wir ", " bitte ", " sehr ", " geehrte ",
            " ihnen ", " oder ", " eine ", " einen ", " dem ", " des ");
    private static final List<String> ENGLISH = List.of(" the ", " and ", " of ", " to ", " you ",
            " we ", " is ", " for ", " with ", " please ", " dear ", " your ", " that ", " this ",
            " are ", " have ", " from ");
    private static final List<String> RUSSIAN = List.of(" и ", " не ", " в ", " на ", " что ", " с ",
            " по ", " для ", " это ", " вы ", " мы ", " как ", " или ", " уважаемые ", " прошу ");

    private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");
    private static final Pattern GERMAN_SPECIFIC = Pattern.compile("[äöüßÄÖÜ]");

    private final DocumentRepository documents;

    public LanguageDetectStepHandler(DocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public DocumentProcessingStep.Step step() {
        return DocumentProcessingStep.Step.LANGUAGE_DETECT;
    }

    @Override
    public StepResult handle(ProcessingContext context) {
        Document document = context.document();
        String text = document.getExtractedText();
        if (text == null || text.isBlank()) {
            // Without text there is nothing to detect; the page set carries no language information
            // either, so the field stays empty rather than being guessed from the file name.
            return StepResult.succeeded(Map.of("language", "", "reason", "No text available"),
                    Document.ProcessingStatus.CLASSIFYING);
        }

        Map<String, Integer> scores = new LinkedHashMap<>();
        String padded = " " + text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
        scores.put("de", countOccurrences(padded, GERMAN) + countMatches(text, GERMAN_SPECIFIC) * 2);
        scores.put("en", countOccurrences(padded, ENGLISH));
        scores.put("ru", countOccurrences(padded, RUSSIAN) + countMatches(text, CYRILLIC) / 10);

        Map.Entry<String, Integer> best = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow();
        int total = scores.values().stream().mapToInt(Integer::intValue).sum();
        double share = total == 0 ? 0 : (double) best.getValue() / total;

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("scores", scores);

        if (best.getValue() < 3 || share < 0.6) {
            diagnostics.put("language", "");
            diagnostics.put("reason", "Not enough evidence to name a language");
            return StepResult.succeeded(diagnostics, Document.ProcessingStatus.CLASSIFYING);
        }

        document.setLanguage(best.getKey());
        documents.save(document);
        diagnostics.put("language", best.getKey());
        diagnostics.put("share", Math.round(share * 100) / 100.0);
        return StepResult.succeeded(diagnostics, Document.ProcessingStatus.CLASSIFYING);
    }

    private static int countOccurrences(String paddedText, List<String> words) {
        int count = 0;
        for (String word : words) {
            int index = paddedText.indexOf(word);
            while (index >= 0) {
                count++;
                index = paddedText.indexOf(word, index + 1);
            }
        }
        return count;
    }

    private static int countMatches(String text, Pattern pattern) {
        var matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}

package de.buerokratiecopilot.documents.ocr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OCR through the Tesseract command line client (spec §9.2: Tesseract for local OCR).
 *
 * <p>The TSV output is used instead of plain text because it carries what the specification asks
 * for: the coordinates of every text block and the engine's confidence per word. Without those a
 * reviewer cannot be shown the fragment a value came from, and an uncertain recognition cannot be
 * distinguished from a certain one.</p>
 *
 * <p>Failure handling mirrors the malware scanner: an engine that cannot run, times out or answers
 * with something unexpected produces a failure, never an empty success. "We could not read this
 * page" and "this page is empty" lead to different follow-up actions.</p>
 */
public class TesseractOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);

    private final String executable;
    private final Duration timeout;

    public TesseractOcrEngine(String executable, Duration timeout) {
        this.executable = executable;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "tesseract";
    }

    @Override
    public boolean isAvailable() {
        return !availableLanguages().isEmpty();
    }

    @Override
    public List<String> availableLanguages() {
        try {
            Process process = new ProcessBuilder(List.of(executable, "--list-langs"))
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            List<String> languages = new ArrayList<>();
            for (String line : output.split("\\R")) {
                String candidate = line.strip().toLowerCase(Locale.ROOT);
                if (candidate.matches("[a-z]{3}(_[a-z]+)?") && !candidate.contains("list")) {
                    languages.add(candidate);
                }
            }
            return List.copyOf(languages);
        }
        catch (IOException ex) {
            return List.of();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    @Override
    public Result recognise(byte[] image, List<String> languages) {
        String languageSpec = languageSpec(languages);
        if (image == null || image.length == 0) {
            return Result.failure(name(), languageSpec, "The page image is empty");
        }
        try {
            // Reading from stdin and writing to stdout avoids temporary files with document content
            // on disk, which matters for a platform that stores personal data.
            ProcessBuilder builder = new ProcessBuilder(List.of(executable, "stdin", "stdout",
                    "-l", languageSpec, "tsv"));
            builder.redirectErrorStream(false);
            Process process = builder.start();

            try (var stdin = process.getOutputStream()) {
                stdin.write(image);
            }
            String tsv = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Result.failure(name(), languageSpec,
                        "The OCR engine did not answer within " + timeout);
            }
            if (process.exitValue() != 0) {
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("Tesseract exited with {}: {}", process.exitValue(), error.strip());
                return Result.failure(name(), languageSpec, "The OCR engine failed: " + error.strip());
            }

            return parseTsv(tsv, languageSpec);
        }
        catch (IOException ex) {
            log.warn("The OCR engine could not be executed", ex);
            return Result.failure(name(), languageSpec, "The OCR engine could not be executed");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.failure(name(), languageSpec, "The OCR was interrupted");
        }
    }

    /**
     * Parses the TSV output of Tesseract.
     *
     * <p>Words are grouped by block so the coordinates describe a paragraph rather than a single
     * word, and the confidence is the mean over all words — and only over words that carry one:
     * punctuation and layout words are reported with {@code -1} and averaging them in would drag a
     * good recognition towards zero.</p>
     */
    static Result parseTsv(String tsv, String language) {
        Map<String, List<OcrEngine.Result.Block>> blocksByKey = new LinkedHashMap<>();
        Map<String, int[]> bounds = new LinkedHashMap<>();
        StringBuilder fullText = new StringBuilder();
        double confidenceSum = 0;
        int confidenceCount = 0;
        int blockIndex = 0;

        for (String line : tsv.split("\\R")) {
            if (line.isBlank() || line.startsWith("level")) {
                continue;
            }
            String[] columns = line.split("\t", -1);
            if (columns.length < 12) {
                continue;
            }
            String level = columns[0].strip();
            String text = columns[11].strip();
            if (!"5".equals(level) || text.isEmpty()) {
                continue;
            }
            int left = parseInt(columns[6]);
            int top = parseInt(columns[7]);
            int width = parseInt(columns[8]);
            int height = parseInt(columns[9]);
            double confidence = parseDouble(columns[10]);
            String key = columns[2] + "-" + columns[3];

            if (!blocksByKey.containsKey(key)) {
                blockIndex++;
                blocksByKey.put(key, new ArrayList<>());
                bounds.put(key, new int[] { left, top, left + width, top + height });
            }
            int[] box = bounds.get(key);
            box[0] = Math.min(box[0], left);
            box[1] = Math.min(box[1], top);
            box[2] = Math.max(box[2], left + width);
            box[3] = Math.max(box[3], top + height);

            blocksByKey.get(key).add(new OcrEngine.Result.Block(left, top, width, height, text,
                    confidence >= 0 ? toFraction(confidence) : null));
            fullText.append(text).append(' ');
            if (confidence >= 0) {
                confidenceSum += confidence;
                confidenceCount++;
            }
        }

        List<OcrEngine.Result.Block> blocks = new ArrayList<>();
        for (Map.Entry<String, List<OcrEngine.Result.Block>> entry : blocksByKey.entrySet()) {
            int[] box = bounds.get(entry.getKey());
            String blockText = entry.getValue().stream()
                    .map(OcrEngine.Result.Block::text)
                    .reduce((a, b) -> a + " " + b)
                    .orElse("");
            double mean = entry.getValue().stream()
                    .map(OcrEngine.Result.Block::confidence)
                    .filter(java.util.Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .average().orElse(Double.NaN);
            blocks.add(new OcrEngine.Result.Block(box[0], box[1], box[2] - box[0], box[3] - box[1],
                    blockText, Double.isNaN(mean) ? null : mean));
        }

        // Percent from the engine becomes a fraction here and nowhere else, so every consumer can
        // assume the same unit as the classification thresholds and the database column.
        Double confidence = confidenceCount == 0 ? null : toFraction(confidenceSum / confidenceCount);
        String text = fullText.toString().strip().replaceAll("[ \\t]{2,}", " ");
        return new Result(text, confidence, blocks, "tesseract", language, null);
    }

    /** Joins the requested languages, keeping only those the engine actually has. */
    String languageSpec(List<String> requested) {
        List<String> available = availableLanguages();
        if (available.isEmpty()) {
            return requested == null || requested.isEmpty() ? "eng" : String.join("+", requested);
        }
        List<String> usable = new ArrayList<>();
        if (requested != null) {
            for (String language : requested) {
                if (language != null && available.contains(language.toLowerCase(Locale.ROOT))) {
                    usable.add(language.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (usable.isEmpty()) {
            usable.add(available.contains("deu") ? "deu" : available.get(0));
        }
        return String.join("+", usable);
    }

    private static int parseInt(String value) {
        try {
            return (int) Double.parseDouble(value.strip());
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.strip());
        }
        catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * The engine reports confidence in percent (0..100), the platform stores a fraction (0..1).
     * Applying the conversion in exactly one place keeps the page column, the thresholds and the API
     * in the same unit.
     */
    static Double toFraction(Double percent) {
        return percent == null ? null : Math.max(0, Math.min(1, percent / 100.0));
    }
}

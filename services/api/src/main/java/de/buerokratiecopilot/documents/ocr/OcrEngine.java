package de.buerokratiecopilot.documents.ocr;

import java.util.List;

/**
 * Optical character recognition (spec §9.2).
 *
 * <p>The specification requires the engine to be configurable and the deployment to be able to run
 * without one. The second part is the harder one: a document whose pages were never recognised must
 * be reported as such, so {@link #isAvailable()} and {@link Result#recognised()} exist to make the
 * difference between "no text" and "not examined" explicit.</p>
 */
public interface OcrEngine {

    /** Name recorded with every page, so a result can always be traced back to its engine. */
    String name();

    /** Whether the engine is configured and usable right now. */
    boolean isAvailable();

    /** Languages the engine has data for; a request outside this set fails instead of guessing. */
    List<String> availableLanguages();

    /**
     * Recognises text on one page image.
     *
     * @param image     PNG or TIFF bytes of the page
     * @param languages requested languages in priority order, for example {@code deu}, {@code eng}
     */
    Result recognise(byte[] image, List<String> languages);

    /**
     * What the engine produced.
     *
     * @param text       recognised text; empty when the page carries no text at all
     * @param confidence average confidence in the range 0..1, or {@code null} when the engine does
     *                   not report one — a made-up value would be worse than a missing one
     * @param blocks     text blocks with their coordinates, when the engine provides them
     * @param engine     engine name
     * @param language   language string actually used
     * @param error      reason for a failed recognition, {@code null} on success
     */
    record Result(String text, Double confidence, List<Block> blocks, String engine, String language,
                  String error) {

        /** Coordinates are in image pixels, origin top-left, as the engine reports them. */
        public record Block(int left, int top, int width, int height, String text, Double confidence) {
        }

        public static Result empty(String engine, String language) {
            return new Result("", null, List.of(), engine, language, null);
        }

        public static Result failure(String engine, String language, String error) {
            return new Result(null, null, List.of(), engine, language, error);
        }

        public boolean recognised() {
            return error == null;
        }

        public boolean hasText() {
            return recognised() && text != null && !text.isBlank();
        }
    }
}

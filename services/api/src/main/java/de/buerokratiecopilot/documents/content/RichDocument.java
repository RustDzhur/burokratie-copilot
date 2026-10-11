package de.buerokratiecopilot.documents.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * A document with formatting, as data (spec §6, E-004).
 *
 * <p>The content is a list of blocks rather than a blob of markup. That is a decision about who owns the
 * truth: markup belongs to whichever editor produced it, and a platform that stored markup would be unable
 * to export a document once the editor changed — which is precisely the moment somebody needs the export.
 * A list of blocks can be rendered into any format, including ones that do not exist yet.</p>
 *
 * <p>Blocks carry their own text and nothing else that has to be interpreted. A heading is a heading because
 * it says so, not because a line begins with a number of hashes.</p>
 */
public final class RichDocument {

    /** One piece of content. */
    public sealed interface Block {

        /** A heading at a level, one being the highest. */
        record Heading(int level, String text) implements Block {
        }

        /** A paragraph of text. */
        record Paragraph(String text) implements Block {
        }

        /** A list item; ordered lists are numbered, unordered ones are not. */
        record ListItem(String text, boolean ordered, int depth) implements Block {
        }

        /** A table with a header row. */
        record Table(List<String> headers, List<List<String>> rows) implements Block {
        }

        /** An image, with the size it is drawn at. */
        record Image(byte[] data, String mimeType, float widthPoints, float heightPoints)
                implements Block {
        }

        /** An explicit break, for a document whose sections must start on a new page. */
        record PageBreak() implements Block {
        }
    }

    /**
     * A document.
     *
     * @param header text repeated at the top of every page
     * @param footer text repeated at the bottom of every page
     * @param numbered whether pages are numbered
     */
    public record Document(String title, List<Block> blocks, String header, String footer,
                           boolean numbered, String reference) {

        /** The document as a map, for a caller that stores or transmits it. */
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", title);
            map.put("header", header);
            map.put("footer", footer);
            map.put("numbered", numbered);
            map.put("reference", reference);
            map.put("blocks", blocks.stream().map(RichDocument::describe).toList());
            return map;
        }
    }

    private RichDocument() {
    }

    /** A document from a client, checked rather than trusted. */
    @SuppressWarnings("unchecked")
    public static Document parse(Object json) {
        if (!(json instanceof Map<?, ?> map)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Inhalt ist kein Dokument");
        }
        List<Block> blocks = new ArrayList<>();
        Object raw = map.get("blocks");
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> block) {
                    blocks.add(block((Map<String, Object>) block));
                }
            }
        }
        return new Document(string(map.get("title")), List.copyOf(blocks), string(map.get("header")),
                string(map.get("footer")), Boolean.TRUE.equals(map.get("numbered")),
                string(map.get("reference")));
    }

    private static Block block(Map<String, Object> block) {
        String type = string(block.get("type"));
        return switch (type == null ? "" : type.toUpperCase(java.util.Locale.ROOT)) {
            case "HEADING" -> new Block.Heading(number(block.get("level"), 1), required(block, "text"));
            case "PARAGRAPH" -> new Block.Paragraph(required(block, "text"));
            case "LIST_ITEM" -> new Block.ListItem(required(block, "text"),
                    Boolean.TRUE.equals(block.get("ordered")), number(block.get("depth"), 0));
            case "TABLE" -> new Block.Table(strings(block.get("headers")), rows(block.get("rows")));
            case "IMAGE" -> new Block.Image(bytes(block), string(block.get("mimeType")),
                    number(block.get("widthPoints"), 400), number(block.get("heightPoints"), 300));
            case "PAGE_BREAK" -> new Block.PageBreak();
            default -> throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Unbekannter Blocktyp: " + type);
        };
    }

    /**
     * A heading level outside one to six is refused.
     *
     * <p>A level seven is not a heading, it is a typo, and every format that accepts it will render it as
     * something the author did not intend.</p>
     */
    static int headingLevel(int level) {
        if (level < 1 || level > 6) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Eine Überschrift hat die Ebene 1 bis 6, nicht " + level);
        }
        return level;
    }

    /** A short description of a block, for a caller that lists a document. */
    public static Map<String, Object> describe(Block block) {
        Map<String, Object> map = new LinkedHashMap<>();
        switch (block) {
            case Block.Heading heading -> {
                map.put("type", "HEADING");
                map.put("level", headingLevel(heading.level()));
                map.put("text", heading.text());
            }
            case Block.Paragraph paragraph -> {
                map.put("type", "PARAGRAPH");
                map.put("text", paragraph.text());
            }
            case Block.ListItem item -> {
                map.put("type", "LIST_ITEM");
                map.put("text", item.text());
                map.put("ordered", item.ordered());
                map.put("depth", Math.max(0, item.depth()));
            }
            case Block.Table table -> {
                map.put("type", "TABLE");
                map.put("headers", table.headers());
                map.put("rows", table.rows());
            }
            case Block.Image image -> {
                map.put("type", "IMAGE");
                map.put("mimeType", image.mimeType());
                map.put("bytes", image.data() == null ? 0 : image.data().length);
            }
            case Block.PageBreak ignored -> map.put("type", "PAGE_BREAK");
        }
        return map;
    }

    /** How many pages a document is likely to need, for a caller that reports on it. */
    public static int blockCount(Document document) {
        return document.blocks().size();
    }

    private static String required(Map<String, Object> block, String key) {
        String value = string(block.get(key));
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ein Block vom Typ " + block.get("type") + " braucht das Feld '" + key + "'");
        }
        return value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && text.matches("\\d+")) {
            return Integer.parseInt(text);
        }
        return fallback;
    }

    private static float number(Object value, float fallback) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return fallback;
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                result.add(string(entry));
            }
        }
        return List.copyOf(result);
    }

    private static List<List<String>> rows(Object value) {
        List<List<String>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object row : list) {
                result.add(strings(row));
            }
        }
        return List.copyOf(result);
    }

    private static byte[] bytes(Map<String, Object> block) {
        Object value = block.get("data");
        if (value instanceof byte[] data) {
            return data;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return java.util.Base64.getDecoder().decode(text);
            }
            catch (IllegalArgumentException ex) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Das Bild ist nicht als Base64 lesbar");
            }
        }
        throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Bild braucht Daten");
    }
}

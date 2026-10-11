package de.buerokratiecopilot.templates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a document template (spec §12, E-003).
 *
 * <p>Two rules shape this class, and both exist because of what a document is.</p>
 *
 * <p><strong>An unknown variable blocks generation.</strong> Not a warning, not a placeholder left in
 * the text: a letter that goes out reading {@code {{Kunde.Name}}} is worse than one that was never
 * written, because nobody notices until the recipient does. The refusal names the variable and every
 * other variable it could not resolve, so a user can fix them in one pass instead of one per attempt.</p>
 *
 * <p><strong>A missing value is never silently replaced by nothing.</strong> An empty string in the
 * middle of a sentence produces a document that reads as if it were complete: "Sehr geehrte ," looks
 * like a mistake but "Der Vertrag endet am ." can look like a fact. Where a template declares a
 * default, the default is used; where it does not, generation stops and says which field is empty.</p>
 *
 * <p>A value that is present and empty is treated exactly like a value that is absent. The distinction
 * matters to a database and not at all to a reader.</p>
 */
public final class TemplateRenderer {

    /** A placeholder is {@code {{Source.Path}}}, with optional spaces inside the braces. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*)\\s*}}");

    /** What an attempt to render produced. */
    public record Result(String text, List<String> usedFields, List<String> unresolved,
                         List<String> emptyRequired) {

        /** Whether the document may be produced at all. */
        public boolean isRenderable() {
            return unresolved.isEmpty() && emptyRequired.isEmpty();
        }

        /** One sentence a user can act on, naming everything that has to be fixed. */
        public String problem() {
            if (isRenderable()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            if (!unresolved.isEmpty()) {
                parts.add("Unbekannte Platzhalter: " + String.join(", ", unresolved)
                        + ". Diese Felder gibt es im Vorlagenkatalog nicht.");
            }
            if (!emptyRequired.isEmpty()) {
                parts.add("Pflichtfelder ohne Wert: " + String.join(", ", emptyRequired)
                        + ". Eine Vorlage wird nicht mit leeren Pflichtfeldern erzeugt.");
            }
            return String.join(" ", parts);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("renderable", isRenderable());
            map.put("usedFields", usedFields);
            map.put("unresolved", unresolved);
            map.put("emptyRequired", emptyRequired);
            map.put("problem", problem());
            return map;
        }
    }

    /** A field as the template declares it. */
    public record Field(String fieldKey, String sourceEntity, String sourcePath, boolean required,
                        String defaultValue, String valueType) {

        /** The key a placeholder uses: {@code Kunde.Name} for entity CONTACT and path Name. */
        public String placeholder() {
            String entity = sourceEntity == null ? "CUSTOM"
                    : sourceEntity.substring(0, 1).toUpperCase(Locale.ROOT)
                            + sourceEntity.substring(1).toLowerCase(Locale.ROOT);
            return sourcePath == null || sourcePath.isBlank() ? entity : entity + "." + sourcePath;
        }
    }

    private TemplateRenderer() {
    }

    /**
     * Renders a body with the values available.
     *
     * @param body   the template text
     * @param fields the fields the template declares
     * @param values the values that were resolved, keyed by placeholder
     */
    public static Result render(String body, List<Field> fields, Map<String, String> values) {
        if (body == null) {
            return new Result("", List.of(), List.of(), List.of());
        }
        Map<String, Field> declared = new LinkedHashMap<>();
        for (Field field : fields == null ? List.<Field>of() : fields) {
            declared.put(field.placeholder().toLowerCase(Locale.ROOT), field);
            // A field may also be addressed by its own key, which is how a caller that builds the value
            // map from the field list naturally writes it.
            declared.putIfAbsent(field.fieldKey().toLowerCase(Locale.ROOT), field);
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        }

        Set<String> unresolved = new LinkedHashSet<>();
        Set<String> emptyRequired = new LinkedHashSet<>();
        Set<String> used = new LinkedHashSet<>();

        Matcher matcher = PLACEHOLDER.matcher(body);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String key = name.toLowerCase(Locale.ROOT);
            Field field = declared.get(key);

            if (field == null) {
                // The template asks for something the catalogue does not define. Nothing is written in
                // its place: leaving the braces would ship them, and removing them would hide the fault.
                unresolved.add(name);
                matcher.appendReplacement(rendered, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            used.add(field.fieldKey());

            String value = normalized.get(key);
            if (isBlank(value)) {
                value = normalized.get(field.fieldKey().toLowerCase(Locale.ROOT));
            }
            if (isBlank(value)) {
                value = field.defaultValue();
            }
            if (isBlank(value)) {
                if (field.required()) {
                    // An empty string in the middle of a sentence reads as a complete sentence; the
                    // document is not produced instead.
                    emptyRequired.add(field.fieldKey());
                    matcher.appendReplacement(rendered, Matcher.quoteReplacement(matcher.group(0)));
                    continue;
                }
                // An optional field with nothing to say: the placeholder disappears, and it is recorded
                // as used so the caller can see which fields were actually consumed.
                matcher.appendReplacement(rendered, "");
                continue;
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);

        return new Result(rendered.toString(), List.copyOf(used), List.copyOf(unresolved),
                List.copyOf(emptyRequired));
    }

    /** The placeholders a body uses, whether or not they are known. */
    public static List<String> placeholdersIn(String body) {
        if (body == null) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return List.copyOf(found);
    }

    /** Where each placeholder occurs, so a user can be shown the sentence that would go out wrong. */
    public static Map<String, List<Integer>> positionsIn(String body) {
        Map<String, List<Integer>> positions = new LinkedHashMap<>();
        if (body == null) {
            return positions;
        }
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            positions.computeIfAbsent(matcher.group(1), key -> new ArrayList<>()).add(matcher.start());
        }
        return positions;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package de.buerokratiecopilot.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.templates.TemplateRenderer.Field;
import de.buerokratiecopilot.templates.TemplateRenderer.Result;

/**
 * Verifies rendering a document template (spec §12, E-003).
 *
 * <p>The acceptance criterion is that an unknown variable blocks generation with a clear error. The
 * tests go past the happy path because the failure modes of a template engine all produce a document
 * that looks finished: a placeholder left in the text, an empty string where a name belongs, a value
 * from the wrong source. Each of those is checked here as something that must not happen.</p>
 */
class TemplateRenderTest {

    private static final List<Field> FIELDS = List.of(
            new Field("kontakt_name", "CONTACT", "Name", true, null, "TEXT"),
            new Field("kontakt_ort", "CONTACT", "Ort", false, null, "TEXT"),
            new Field("akte_zeichen", "CASE", "Zeichen", true, null, "TEXT"),
            new Field("vertrag_ende", "CONTRACT", "EndDate", false, "auf unbestimmte Zeit", "DATE"));

    private static Map<String, String> values(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("a complete template renders with the values it was given")
    void completeTemplateRenders() {
        Result result = TemplateRenderer.render(
                "Sehr geehrte {{Contact.Name}}, Ihr Zeichen {{Case.Zeichen}}.",
                FIELDS, values("Contact.Name", "Frau Muster", "Case.Zeichen", "2026-4711"));

        assertThat(result.isRenderable()).isTrue();
        assertThat(result.text()).isEqualTo("Sehr geehrte Frau Muster, Ihr Zeichen 2026-4711.");
        assertThat(result.usedFields()).containsExactlyInAnyOrder("kontakt_name", "akte_zeichen");
        assertThat(result.problem()).isNull();
    }

    @Test
    @DisplayName("a declared default is used when nothing else is available")
    void defaultIsUsed() {
        Result result = TemplateRenderer.render(
                "Der Vertrag läuft {{Contract.EndDate}}.",
                FIELDS, values("Contact.Name", "Frau Muster", "Case.Zeichen", "X"));

        assertThat(result.isRenderable()).isTrue();
        assertThat(result.text()).isEqualTo("Der Vertrag läuft auf unbestimmte Zeit.");
    }

    @Test
    @DisplayName("a given value wins over the default")
    void givenValueWinsOverDefault() {
        Result result = TemplateRenderer.render("Endet am {{Contract.EndDate}}.", FIELDS,
                values("Contract.EndDate", "31.12.2026"));

        assertThat(result.text()).isEqualTo("Endet am 31.12.2026.");
    }

    @Test
    @DisplayName("an unknown variable blocks generation and says which one it is")
    void unknownVariableBlocks() {
        Result result = TemplateRenderer.render(
                "Sehr geehrte {{Contact.Name}}, {{Unbekannt.Feld}}.",
                FIELDS, values("Contact.Name", "Frau Muster"));

        assertThat(result.isRenderable()).isFalse();
        assertThat(result.unresolved()).containsExactly("Unbekannt.Feld");
        assertThat(result.problem())
                .as("the message has to name the variable, or a user cannot fix it")
                .contains("Unbekannt.Feld");
        assertThat(result.text())
                .as("nothing is written in its place: leaving it would ship it, removing it would hide it")
                .contains("{{Unbekannt.Feld}}");
    }

    @Test
    @DisplayName("every unknown variable is reported at once, not one per attempt")
    void allUnknownVariablesAreReported() {
        Result result = TemplateRenderer.render(
                "{{A.Eins}} {{B.Zwei}} {{C.Drei}}", FIELDS, Map.of());

        assertThat(result.unresolved()).containsExactlyInAnyOrder("A.Eins", "B.Zwei", "C.Drei");
        assertThat(result.problem()).contains("A.Eins", "B.Zwei", "C.Drei");
    }

    @Test
    @DisplayName("a required field without a value blocks generation")
    void emptyRequiredFieldBlocks() {
        Result result = TemplateRenderer.render(
                "Sehr geehrte {{Contact.Name}}, Ihr Zeichen {{Case.Zeichen}}.",
                FIELDS, values("Case.Zeichen", "2026-4711"));

        assertThat(result.isRenderable()).isFalse();
        assertThat(result.emptyRequired()).containsExactly("kontakt_name");
        assertThat(result.problem()).contains("Pflichtfeld");
    }

    @Test
    @DisplayName("an empty value is treated like a missing one, because a reader cannot tell them apart")
    void blankValueIsLikeMissing() {
        Result result = TemplateRenderer.render("Sehr geehrte {{Contact.Name}}.", FIELDS,
                values("Contact.Name", "   "));

        assertThat(result.isRenderable()).isFalse();
        assertThat(result.emptyRequired()).containsExactly("kontakt_name");
    }

    @Test
    @DisplayName("an optional field with no value does not leave an empty gap")
    void optionalFieldDisappearsRatherThanLeavingAGap() {
        Result result = TemplateRenderer.render("Ort: {{Contact.Ort}} Ende.", FIELDS,
                values("Contact.Name", "Frau Muster", "Case.Zeichen", "X"));

        assertThat(result.isRenderable()).isTrue();
        assertThat(result.text())
                .as("an empty string would produce a sentence that reads as complete")
                .isEqualTo("Ort:  Ende.");
    }

    @Test
    @DisplayName("a field can be addressed by its own key as well")
    void fieldKeyAlsoResolves() {
        Result result = TemplateRenderer.render("{{kontakt_name}}", FIELDS,
                values("kontakt_name", "Frau Muster"));

        assertThat(result.isRenderable()).isTrue();
        assertThat(result.text()).isEqualTo("Frau Muster");
    }

    @Test
    @DisplayName("spacing inside the braces does not matter")
    void spacingIsTolerated() {
        Result result = TemplateRenderer.render("{{  Contact.Name  }}", FIELDS,
                values("Contact.Name", "Frau Muster"));

        assertThat(result.text()).isEqualTo("Frau Muster");
    }

    @Test
    @DisplayName("the placeholders of a body can be listed without rendering it")
    void placeholdersCanBeListed() {
        assertThat(TemplateRenderer.placeholdersIn("{{A.B}} und {{C.D}} und {{A.B}}"))
                .containsExactly("A.B", "C.D", "A.B");
        assertThat(TemplateRenderer.positionsIn("{{A.B}} und {{A.B}}").get("A.B"))
                .as("a user is shown the sentence that would go out wrong")
                .hasSize(2);
    }

    @Test
    @DisplayName("a text without placeholders renders unchanged")
    void plainTextRenders() {
        Result result = TemplateRenderer.render("Ein Text ohne Platzhalter.", FIELDS, Map.of());

        assertThat(result.isRenderable()).isTrue();
        assertThat(result.text()).isEqualTo("Ein Text ohne Platzhalter.");
        assertThat(result.usedFields()).isEmpty();
    }

    @Test
    @DisplayName("braces that are not placeholders are left alone")
    void strayBracesAreLeftAlone() {
        Result result = TemplateRenderer.render("Ein { einzelnes } Zeichen.", FIELDS, Map.of());

        assertThat(result.isRenderable()).isTrue();
        assertThat(result.text()).isEqualTo("Ein { einzelnes } Zeichen.");
    }

    @Test
    @DisplayName("a null body renders as empty rather than failing")
    void nullBodyRendersEmpty() {
        assertThat(TemplateRenderer.render(null, FIELDS, Map.of()).text()).isEmpty();
    }

    @Test
    @DisplayName("the result carries everything a user needs to act on")
    void resultCarriesItsDiagnostics() {
        Result result = TemplateRenderer.render("{{Contact.Name}} {{Nichts.Da}}", FIELDS, Map.of());

        Map<String, Object> diagnostics = result.asMap();
        assertThat(diagnostics).containsEntry("renderable", false)
                .containsEntry("problem", result.problem());
        assertThat(diagnostics.get("unresolved")).isEqualTo(List.of("Nichts.Da"));
        assertThat(diagnostics.get("emptyRequired")).isEqualTo(List.of("kontakt_name"));
    }
}

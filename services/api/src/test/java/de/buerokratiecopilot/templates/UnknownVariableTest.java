package de.buerokratiecopilot.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies that an unknown variable blocks generation (spec §12, E-003 acceptance).
 *
 * <p>The criterion is not that the platform reports the problem but that it refuses to produce the
 * document. A template engine that returns a half-filled letter together with a warning has produced the
 * letter, and somebody will send it — so the test checks that nothing was written and that the message
 * names what has to be fixed.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class UnknownVariableTest {

    @Autowired
    private TemplateService templates;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        UUID id = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Vorlagen Test', 'COMPANY') returning id
                """, UUID.class, "tpl-" + UUID.randomUUID().toString().substring(0, 12));
        // The tenant is established for the rest of the test, so that every service call below acts in
        // it - which is what the protected tables require and what the HTTP layer does in production.
        RequestContext.setTenantId(id);
        return id;
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "tf-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    private UUID publishedTemplate(UUID tenantId, String bodyHtml) {
        // This helper is called for a tenant that is not always the one set for the test - one test prepares
        // a template for its owner and then tries to generate from a stranger's side - so it names the tenant
        // it acts in rather than relying on the ambient one.
        return RequestContext.runAs(tenantId, () -> defineAndPublish(tenantId, bodyHtml));
    }

    private UUID defineAndPublish(UUID tenantId, String bodyHtml) {
        UUID templateId = templates.define(new TemplateService.TemplateRequest(tenantId, "Mahnung",
                "Mahnung", "Erste Mahnung", "REMINDER",
                List.of(new TemplateService.FieldRequest("kontakt_name", "Name", "CONTACT", "Name", "TEXT",
                                true, null),
                        new TemplateService.FieldRequest("akte_zeichen", "Zeichen", "CASE", "Zeichen",
                                "TEXT", true, null)),
                bodyHtml, "Mahnung {{Case.Zeichen}}", person()));
        templates.publish(tenantId, templateId, 1, person());
        return templateId;
    }

    @Test
    @DisplayName("a template using an undefined placeholder is refused when it is defined")
    void undefinedPlaceholderIsRefusedAtDefinition() {
        UUID tenantId = tenant();

        // Found by the person editing the template rather than by whoever tries to send the letter.
        assertThatThrownBy(() -> templates.define(new TemplateService.TemplateRequest(tenantId, "X",
                "Kaputt", null, "LETTER",
                List.of(new TemplateService.FieldRequest("kontakt_name", "Name", "CONTACT", "Name", "TEXT",
                        true, null)),
                "Sehr geehrte {{Contact.Name}}, {{Nicht.Definiert}}.", null, person())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Nicht.Definiert");
    }

    @Test
    @DisplayName("an unknown variable blocks generation and nothing is produced")
    void unknownVariableBlocksGeneration() {
        UUID tenantId = tenant();
        UUID templateId = publishedTemplate(tenantId, "Sehr geehrte {{Contact.Name}}.");

        // The value map carries a key the template does not declare; the renderer only resolves what
        // the catalogue holds, so this is not what makes it fail. The failure comes from the body.
        assertThatThrownBy(() -> templates.generate(tenantId, templateId,
                Map.of("Contact.Name", "Frau Muster")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("akte_zeichen");
    }

    @Test
    @DisplayName("the refusal names every problem at once")
    void refusalNamesEveryProblem() {
        UUID tenantId = tenant();
        UUID templateId = publishedTemplate(tenantId, "Sehr geehrte {{Contact.Name}}, {{Case.Zeichen}}.");

        assertThatThrownBy(() -> templates.generate(tenantId, templateId, Map.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    String message = ex.getMessage();
                    assertThat(message).contains("kontakt_name");
                    assertThat(message).contains("akte_zeichen");
                });
    }

    @Test
    @DisplayName("a complete set of values produces the document")
    void completeValuesProduceTheDocument() {
        UUID tenantId = tenant();
        UUID templateId = publishedTemplate(tenantId, "Sehr geehrte {{Contact.Name}}, {{Case.Zeichen}}.");

        TemplateService.Generated generated = templates.generate(tenantId, templateId,
                Map.of("Contact.Name", "Frau Muster", "Case.Zeichen", "2026-4711"));

        assertThat(generated.body()).isEqualTo("Sehr geehrte Frau Muster, 2026-4711.");
        assertThat(generated.subject()).isEqualTo("Mahnung 2026-4711");
        assertThat(generated.usedFields()).containsExactlyInAnyOrder("kontakt_name", "akte_zeichen");
    }

    @Test
    @DisplayName("an unpublished version is not used for generation")
    void unpublishedVersionIsNotUsed() {
        UUID tenantId = tenant();
        UUID templateId = templates.define(new TemplateService.TemplateRequest(tenantId, "Entwurf",
                "Entwurf", null, "LETTER",
                List.of(new TemplateService.FieldRequest("kontakt_name", "Name", "CONTACT", "Name", "TEXT",
                        true, null)),
                "Sehr geehrte {{Contact.Name}}.", null, person()));

        assertThatThrownBy(() -> templates.generate(tenantId, templateId,
                Map.of("Contact.Name", "Frau Muster")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("veröffentlicht");
    }

    @Test
    @DisplayName("an unknown source entity is refused when a field is defined")
    void unknownSourceEntityIsRefused() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> templates.define(new TemplateService.TemplateRequest(tenantId, "X",
                "Falsche Quelle", null, "LETTER",
                List.of(new TemplateService.FieldRequest("k", "K", "WIKIPEDIA", "Name", "TEXT", true,
                        null)),
                "{{Wikipedia.Name}}", null, person())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Quelle");
    }

    @Test
    @DisplayName("an unknown category is refused")
    void unknownCategoryIsRefused() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> templates.define(new TemplateService.TemplateRequest(tenantId, "X",
                "Kategorie", null, "POSTKARTE", List.of(), "Text", null, person())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Kategorie");
    }

    @Test
    @DisplayName("the catalogue tells an editor what may be inserted")
    void catalogueListsWhatMayBeInserted() {
        UUID tenantId = tenant();
        UUID templateId = publishedTemplate(tenantId, "Sehr geehrte {{Contact.Name}}.");

        var catalogue = templates.catalogue(tenantId, templateId);

        assertThat(catalogue).hasSize(2);
        assertThat(catalogue.getFirst()).containsEntry("placeholder", "Contact.Name")
                .containsEntry("required", true);
    }

    @Test
    @DisplayName("a template of another tenant is not generated")
    void otherTenantCannotGenerate() {
        UUID owner = tenant();
        UUID stranger = tenant();
        UUID templateId = publishedTemplate(owner, "Sehr geehrte {{Contact.Name}}.");

        assertThatThrownBy(() -> templates.generate(stranger, templateId,
                Map.of("Contact.Name", "Frau Muster", "Case.Zeichen", "X")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("publishing needs a person")
    void publishingNeedsAPerson() {
        UUID tenantId = tenant();
        UUID templateId = publishedTemplate(tenantId, "Text");

        assertThatThrownBy(() -> templates.publish(tenantId, templateId, 1, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Person");
    }

    @Test
    @DisplayName("the generated document is not written when generation is refused")
    void nothingIsWrittenWhenRefused() {
        UUID tenantId = tenant();
        UUID templateId = publishedTemplate(tenantId, "Sehr geehrte {{Contact.Name}}, {{Case.Zeichen}}.");

        assertThatThrownBy(() -> templates.generate(tenantId, templateId, Map.of()))
                .isInstanceOf(ApiException.class);

        assertThat(jdbc.queryForObject("select count(*) from generated_documents where tenant_id = ?",
                Integer.class, tenantId))
                .as("a half-filled letter is a letter somebody sends")
                .isZero();
    }
}

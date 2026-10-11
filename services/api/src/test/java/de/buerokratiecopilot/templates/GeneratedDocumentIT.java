package de.buerokratiecopilot.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies that a generated document is kept (spec §15, E-003; the E-003b gap).
 *
 * <p>The rule that carries this is that a document with unfilled fields is not a finished document. The
 * status is derived from what is actually unresolved and there is no parameter to assert otherwise,
 * because a finished-looking letter with a blank where the amount belongs is sent, filed, and discovered
 * months later by somebody who has to reconstruct what was meant.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class GeneratedDocumentIT {

    @Autowired
    private GeneratedDocumentService generated;

    @Autowired
    private JdbcTemplate jdbc;

    private record Tenant(UUID tenantId, UUID userId) {
    }

    private Tenant tenant() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Vorlage Test', 'COMPANY') returning id
                """, UUID.class, "gd-" + UUID.randomUUID().toString().substring(0, 12));
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Person', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "gd-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', true)
                """, tenantId, userId);
        return new Tenant(tenantId, userId);
    }

    private GeneratedDocumentService.Request request(String title, List<String> unresolved) {
        return new GeneratedDocumentService.Request(null, null, null, "case", UUID.randomUUID(), title,
                "PDF", "<p>Sehr geehrte Damen und Herren,</p>", Map.of("recipient", "Behörde"), unresolved);
    }

    // ---------------------------------------------------------------- storing

    @Test
    @DisplayName("a generated document is kept and can be found again")
    void aGeneratedDocumentIsKept() {
        Tenant tenant = tenant();

        GeneratedDocumentService.Stored stored = RequestContext.runAs(tenant.tenantId(),
                () -> generated.record(tenant.tenantId(), request("Antwort", List.of()), tenant.userId()));

        assertThat(stored.status()).isEqualTo("GENERATED");
        assertThat(stored.isReady()).isTrue();
        Map<String, Object> described = RequestContext.runAs(tenant.tenantId(),
                () -> generated.describe(tenant.tenantId(), stored.id()));
        assertThat(described).containsEntry("title", "Antwort");
        assertThat(described).containsEntry("output_format", "PDF");
    }

    @Test
    @DisplayName("a document with unfilled fields is not a finished document")
    void aDocumentWithUnfilledFieldsIsBlocked() {
        Tenant tenant = tenant();

        GeneratedDocumentService.Stored stored = RequestContext.runAs(tenant.tenantId(),
                () -> generated.record(tenant.tenantId(),
                        request("Antwort", List.of("Betrag", "Frist")), tenant.userId()));

        assertThat(stored.status())
                .as("a letter with a blank where the amount belongs gets sent and discovered months later")
                .isEqualTo("BLOCKED_UNRESOLVED_FIELDS");
        assertThat(stored.isReady()).isFalse();
        assertThat(stored.unresolvedFields()).containsExactly("Betrag", "Frist");
    }

    @Test
    @DisplayName("the status cannot be asserted by the caller, only derived")
    void theStatusIsDerivedAndNotAccepted() {
        Tenant tenant = tenant();

        // There is no parameter for it: a caller cannot announce a document as ready while it has holes.
        GeneratedDocumentService.Stored blocked = RequestContext.runAs(tenant.tenantId(),
                () -> generated.record(tenant.tenantId(), request("Mit Lücke", List.of("Betrag")),
                        tenant.userId()));

        assertThat(jdbc.queryForObject("select status from generated_documents where id = ?", String.class,
                blocked.id())).isEqualTo("BLOCKED_UNRESOLVED_FIELDS");
    }

    @Test
    @DisplayName("blank field names do not count as unresolved")
    void blankFieldNamesDoNotCount() {
        Tenant tenant = tenant();

        GeneratedDocumentService.Stored stored = RequestContext.runAs(tenant.tenantId(),
                () -> generated.record(tenant.tenantId(), request("Antwort", java.util.Arrays.asList("", "  ", null)),
                        tenant.userId()));

        assertThat(stored.status()).isEqualTo("GENERATED");
    }

    @Test
    @DisplayName("what is missing can be read back as a list")
    void whatIsMissingCanBeReadBack() {
        Tenant tenant = tenant();
        GeneratedDocumentService.Stored stored = RequestContext.runAs(tenant.tenantId(),
                () -> generated.record(tenant.tenantId(), request("Antwort", List.of("Betrag", "Frist")),
                        tenant.userId()));

        assertThat(RequestContext.runAs(tenant.tenantId(),
                () -> generated.unresolvedFields(tenant.tenantId(), stored.id())))
                .containsExactly("Betrag", "Frist");
    }

    @Test
    @DisplayName("the documents that are waiting on somebody can be counted")
    void blockedDocumentsAreCounted() {
        Tenant tenant = tenant();
        RequestContext.runAs(tenant.tenantId(), () -> {
            generated.record(tenant.tenantId(), request("Fertig", List.of()), tenant.userId());
            generated.record(tenant.tenantId(), request("Offen", List.of("Betrag")), tenant.userId());
            return null;
        });

        assertThat(RequestContext.runAs(tenant.tenantId(),
                () -> generated.blockedCount(tenant.tenantId()))).isEqualTo(1);
    }

    // ---------------------------------------------------------------- finding

    @Test
    @DisplayName("the documents of one subject are listed, newest first")
    void documentsOfASubjectAreListed() {
        Tenant tenant = tenant();
        UUID subjectId = UUID.randomUUID();
        RequestContext.runAs(tenant.tenantId(), () -> {
            generated.record(tenant.tenantId(), new GeneratedDocumentService.Request(null, null, null,
                    "case", subjectId, "Erste", "PDF", "<p>a</p>", Map.of(), List.of()), tenant.userId());
            generated.record(tenant.tenantId(), new GeneratedDocumentService.Request(null, null, null,
                    "case", subjectId, "Zweite", "DOCX", "<p>b</p>", Map.of(), List.of()), tenant.userId());
            return null;
        });

        var documents = RequestContext.runAs(tenant.tenantId(),
                () -> generated.forSubject(tenant.tenantId(), "case", subjectId));

        assertThat(documents).hasSize(2);
        assertThat(documents.getFirst()).containsEntry("title", "Zweite");
    }

    @Test
    @DisplayName("the documents of another tenant are not reachable")
    void anotherTenantsDocumentIsNotReachable() {
        Tenant owner = tenant();
        Tenant stranger = tenant();
        GeneratedDocumentService.Stored stored = RequestContext.runAs(owner.tenantId(),
                () -> generated.record(owner.tenantId(), request("Antwort", List.of()), owner.userId()));

        assertThatThrownBy(() -> RequestContext.runAs(stranger.tenantId(),
                () -> generated.describe(stranger.tenantId(), stored.id())))
                .isInstanceOf(ApiException.class);
        assertThat(RequestContext.runAs(stranger.tenantId(),
                () -> generated.list(stranger.tenantId(), 50))).isEmpty();
    }

    @Test
    @DisplayName("an unknown document is reported")
    void anUnknownDocumentIsReported() {
        Tenant tenant = tenant();

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(),
                () -> generated.describe(tenant.tenantId(), UUID.randomUUID())))
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("a document without a title is refused")
    void aDocumentWithoutATitleIsRefused() {
        Tenant tenant = tenant();

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(),
                () -> generated.record(tenant.tenantId(), request(null, List.of()), tenant.userId())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Titel");
    }

    @Test
    @DisplayName("an unknown output format is refused")
    void anUnknownFormatIsRefused() {
        Tenant tenant = tenant();
        GeneratedDocumentService.Request odd = new GeneratedDocumentService.Request(null, null, null,
                "case", UUID.randomUUID(), "Antwort", "PAPYRUS", "<p>x</p>", Map.of(), List.of());

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(),
                () -> generated.record(tenant.tenantId(), odd, tenant.userId())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Ausgabeformat");
    }

    @Test
    @DisplayName("the formats the schema allows are the ones accepted")
    void theSchemaFormatsAreTheOnesAccepted() {
        Tenant tenant = tenant();

        for (String format : List.of("PDF", "DOCX", "HTML", "TXT")) {
            GeneratedDocumentService.Request valid = new GeneratedDocumentService.Request(null, null, null,
                    "case", UUID.randomUUID(), "Antwort " + format, format, "<p>x</p>", Map.of(),
                    List.of());
            assertThat(RequestContext.runAs(tenant.tenantId(),
                    () -> generated.record(tenant.tenantId(), valid, tenant.userId())).outputFormat())
                    .isEqualTo(format);
        }
    }

    @Test
    @DisplayName("the field values are kept with the document")
    void fieldValuesAreKept() {
        Tenant tenant = tenant();
        GeneratedDocumentService.Stored stored = RequestContext.runAs(tenant.tenantId(),
                () -> generated.record(tenant.tenantId(), request("Antwort", List.of()), tenant.userId()));

        assertThat(String.valueOf(RequestContext.runAs(tenant.tenantId(),
                () -> generated.describe(tenant.tenantId(), stored.id())).get("field_values")))
                .contains("Behörde");
    }

    @Test
    @DisplayName("what the document said is kept as it was, so a later edit of the template cannot change it")
    void theBodyIsKeptAsItWas() {
        Tenant tenant = tenant();
        String original = "<p>Ursprünglicher Text</p>";
        GeneratedDocumentService.Request request = new GeneratedDocumentService.Request(null, null, null,
                "case", UUID.randomUUID(), "Antwort", "PDF", original, Map.of(), List.of());
        GeneratedDocumentService.Stored stored = RequestContext.runAs(tenant.tenantId(),
                () -> generated.record(tenant.tenantId(), request, tenant.userId()));

        assertThat(RequestContext.runAs(tenant.tenantId(),
                () -> generated.describe(tenant.tenantId(), stored.id())))
                .containsEntry("body_html", original);
    }
}

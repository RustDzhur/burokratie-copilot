package de.buerokratiecopilot.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.documents.content.RichDocument;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies that producing a document and keeping it happen together (spec §6 and §15, E-004).
 *
 * <p>The acceptance clause is that a document is stored and exported. The point of this test is that the
 * two cannot come apart: a letter that was produced and not recorded is a letter nobody can prove was
 * sent.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class GeneratedDocumentExportIT {

    @Autowired
    private GeneratedDocumentExportService production;

    @Autowired
    private GeneratedDocumentService register;

    @Autowired
    private JdbcTemplate jdbc;

    private record Tenant(UUID tenantId, UUID userId) {
    }

    private Tenant tenant() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Erzeugung Test', 'COMPANY') returning id
                """, UUID.class, "ge-" + UUID.randomUUID().toString().substring(0, 12));
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Person', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "ge-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', true)
                """, tenantId, userId);
        return new Tenant(tenantId, userId);
    }

    private RichDocument.Document letter() {
        return new RichDocument.Document("Antwort an die Behörde",
                List.of(new RichDocument.Block.Heading(1, "Betreff: Antrag"),
                        new RichDocument.Block.Paragraph("Sehr geehrte Damen und Herren,"),
                        new RichDocument.Block.ListItem("Anlage 1", false, 0),
                        new RichDocument.Block.Table(List.of("Position", "Betrag"),
                                List.of(List.of("Gebuehr", "35,00")))),
                "Aktenzeichen 123/2026", "Muster GmbH", true, "BC-DOC-EXPORT");
    }

    private GeneratedDocumentExportService.Request request(String format, List<String> unresolved) {
        return new GeneratedDocumentExportService.Request(letter(), null, null, null, "case",
                UUID.randomUUID(), format, Map.of("recipient", "Behoerde"), unresolved);
    }

    // ---------------------------------------------------------------- produced and recorded together

    @Test
    @DisplayName("a document is produced and recorded in one act")
    void producingRecordsIt() {
        Tenant tenant = tenant();

        GeneratedDocumentExportService.Produced produced = RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), request("PDF", List.of()), tenant.userId()));

        assertThat(produced.content()).isNotEmpty();
        assertThat(produced.filename()).endsWith(".pdf");
        assertThat(produced.record().status()).isEqualTo("GENERATED");
        // The record exists, so the letter can be proven later.
        assertThat(RequestContext.runAs(tenant.tenantId(),
                () -> register.describe(tenant.tenantId(), produced.record().id())))
                .containsEntry("output_format", "PDF");
    }

    @Test
    @DisplayName("the produced PDF says what the document said")
    void theProducedPdfSaysWhatItSaid() throws Exception {
        Tenant tenant = tenant();
        GeneratedDocumentExportService.Produced produced = RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), request("PDF", List.of()), tenant.userId()));

        try (PDDocument pdf = Loader.loadPDF(produced.content())) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Antwort an die").contains("Sehr geehrte").contains("35,00");
        }
    }

    @Test
    @DisplayName("the produced DOCX opens and says the same")
    void theProducedDocxSaysTheSame() throws Exception {
        Tenant tenant = tenant();
        GeneratedDocumentExportService.Produced produced = RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), request("DOCX", List.of()), tenant.userId()));

        try (XWPFDocument docx = new XWPFDocument(new java.io.ByteArrayInputStream(produced.content()))) {
            assertThat(docx.getTables())
                    .as("a table flattened into text is a table somebody has to rebuild by hand")
                    .hasSize(1);
        }
        assertThat(produced.record().status()).isEqualTo("GENERATED");
    }

    @Test
    @DisplayName("what the register keeps is the text the document said")
    void theRegisterKeepsTheText() {
        Tenant tenant = tenant();

        GeneratedDocumentExportService.Produced produced = RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), request("TXT", List.of()), tenant.userId()));

        String stored = String.valueOf(RequestContext.runAs(tenant.tenantId(),
                () -> register.describe(tenant.tenantId(), produced.record().id())).get("body_html"));
        assertThat(stored).contains("Sehr geehrte").contains("Anlage 1").contains("Position");
    }

    @Test
    @DisplayName("a letter with a gap is produced but recorded as blocked, not as finished")
    void aLetterWithAGapIsBlocked() {
        Tenant tenant = tenant();

        GeneratedDocumentExportService.Produced produced = RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), request("PDF", List.of("Betrag")),
                        tenant.userId()));

        // Printing a draft is not the same as sending one: somebody has to see what is missing.
        assertThat(produced.content()).isNotEmpty();
        assertThat(produced.record().status()).isEqualTo("BLOCKED_UNRESOLVED_FIELDS");
        assertThat(produced.record().isReady()).isFalse();
        assertThat(String.valueOf(produced.asMap().get("unresolvedFields"))).contains("Betrag");
    }

    @Test
    @DisplayName("the text and HTML a caller asked for are produced as well")
    void theOtherFormatsAreProduced() {
        Tenant tenant = tenant();

        GeneratedDocumentExportService.Produced html = RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), request("HTML", List.of()), tenant.userId()));
        GeneratedDocumentExportService.Produced txt = RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), request("TXT", List.of()), tenant.userId()));

        assertThat(new String(html.content(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("<h1>").contains("Sehr geehrte");
        assertThat(html.mimeType()).isEqualTo("text/html");
        assertThat(new String(txt.content(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("Sehr geehrte");
        assertThat(txt.mimeType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("an angle bracket in a letter does not become markup")
    void anAngleBracketIsEscaped() {
        RichDocument.Document withMarkup = new RichDocument.Document("Test",
                List.of(new RichDocument.Block.Paragraph("Betrag < 100 & mehr")), null, null, false,
                "BC-DOC-ESC");

        String html = GeneratedDocumentExportService.toHtml(withMarkup);

        assertThat(html).contains("&lt;").contains("&amp;").doesNotContain("Betrag < 100");
    }

    @Test
    @DisplayName("the same document produced twice gives two records, because two letters were sent")
    void producingTwiceGivesTwoRecords() {
        Tenant tenant = tenant();

        GeneratedDocumentExportService.Produced first = RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), request("PDF", List.of()), tenant.userId()));
        GeneratedDocumentExportService.Produced second = RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), request("PDF", List.of()), tenant.userId()));

        assertThat(second.record().id()).isNotEqualTo(first.record().id());
        assertThat(RequestContext.runAs(tenant.tenantId(),
                () -> register.list(tenant.tenantId(), 50))).hasSize(2);
    }

    @Test
    @DisplayName("every format the schema stores can be produced")
    void everyStoredFormatCanBeProduced() {
        Tenant tenant = tenant();
        List<String> produced = new ArrayList<>();

        for (String format : GeneratedDocumentExportService.formats()) {
            GeneratedDocumentExportService.Produced result = RequestContext.runAs(tenant.tenantId(),
                    () -> production.produce(tenant.tenantId(), request(format, List.of()),
                            tenant.userId()));
            assertThat(result.content()).as("Format %s", format).isNotEmpty();
            produced.add(result.record().outputFormat());
        }

        assertThat(produced).containsExactlyInAnyOrder("PDF", "DOCX", "HTML", "TXT");
    }

    @Test
    @DisplayName("an unknown format is refused rather than guessed at")
    void anUnknownFormatIsRefused() {
        Tenant tenant = tenant();

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), request("PAPYRUS", List.of()),
                        tenant.userId())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Ausgabeformat");
    }

    @Test
    @DisplayName("a call without a document is refused")
    void aCallWithoutADocumentIsRefused() {
        Tenant tenant = tenant();

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(),
                () -> production.produce(tenant.tenantId(), new GeneratedDocumentExportService.Request(
                        null, null, null, null, "case", UUID.randomUUID(), "PDF", Map.of(), List.of()),
                        tenant.userId())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the text of a document carries its parts, including a note where an image was")
    void theTextCarriesTheParts() {
        RichDocument.Document document = new RichDocument.Document("Titel",
                List.of(new RichDocument.Block.Heading(2, "Abschnitt"),
                        new RichDocument.Block.ListItem("Punkt", true, 0),
                        new RichDocument.Block.Image(new byte[] {1, 2}, "image/png", 10, 10),
                        new RichDocument.Block.PageBreak()),
                null, null, false, null);

        String text = GeneratedDocumentExportService.textOf(document);

        assertThat(text).contains("Abschnitt").contains("1. Punkt").contains("[Bild, 2 Bytes]");
    }
}

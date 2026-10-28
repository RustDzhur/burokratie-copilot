package de.buerokratiecopilot.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * The ELSTER export against a real database: the rows, their amounts, the booking direction, the CSV
 * columns and the empty year (docs/ELSTER_MAPPING.md §5).
 *
 * <p>This is deliberately a {@code *Test} and not a {@code *IT}: the export is the deliverable of the
 * task and must be green with {@code ./mvnw test}, not only with the failsafe run.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class ElsterExportTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;

    private record Session(Cookie cookie, UUID tenantId) {
    }

    /** A fresh owner tenant: the owner role carries finance.view and documents.view. */
    private Session register() throws Exception {
        String email = "elster-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"ELSTER Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));

        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), tenantId);
    }

    private UUID document(Session session, String categoryCode, LocalDate date, String recipient) {
        UUID id = UUID.randomUUID();
        String hash = (id.toString() + UUID.randomUUID()).replace("-", "");
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status, document_date,
                                       recipient, expense_category_id, expense_category_source, categorized_at)
                values (?, ?, ?, ?, 'application/pdf', 100, ?, 'UPLOAD', 'READY', 'NOT_REQUIRED', ?, ?,
                        (select id from expense_categories where code = ?), 'RULE', now())
                """, id, session.tenantId(), "beleg-" + id + ".pdf", "beleg-" + id + ".pdf", hash, date,
                recipient, categoryCode));
        return id;
    }

    private void extraction(Session session, UUID documentId, String field, String value) {
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into document_extractions (document_id, field_key, field_value, normalized_value,
                                                  value_type, source_kind, extractor)
                values (?, ?, ?, ?, 'AMOUNT', 'MANUAL', 'MANUAL')
                """, documentId, field, value, value));
    }

    private void invoice(Session session, UUID documentId, String net, String tax, String gross, LocalDate date) {
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into invoices (tenant_id, direction, invoice_number, net_amount, tax_amount, gross_amount,
                                      document_id, issue_date, status)
                values (?, 'INCOMING', ?, ?::numeric, ?::numeric, ?::numeric, ?, ?, 'RECEIVED')
                """, session.tenantId(), "ELSTER-" + documentId, net, tax, gross, documentId, date));
    }

    private void setting(Session session, String key, String json, String type) {
        RequestContext.runAs(session.tenantId(), () -> {
            jdbc.update("delete from system_settings where tenant_id = ? and setting_key = ?",
                    session.tenantId(), key);
            jdbc.update("""
                    insert into system_settings (tenant_id, setting_key, category, value, value_type, description)
                    values (?, ?, 'OTHER', ?::jsonb, ?, '{}'::jsonb)
                    """, session.tenantId(), key, json, type);
        });
    }

    private MvcResult export(Session session, String year, String format) throws Exception {
        var request = get("/api/v1/exports/elster").cookie(session.cookie());
        if (year != null) {
            request.param("year", year);
        }
        if (format != null) {
            request.param("format", format);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private static LocalDate date(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }

    @Test
    @DisplayName("a year without documents answers 200 with an empty list and zero totals, JSON and CSV")
    void emptyYearIsAValidAnswer() throws Exception {
        Session session = register();

        MvcResult json = export(session, "1999", null);
        assertThat((Integer) JsonPath.read(json.getResponse().getContentAsString(), "$.year")).isEqualTo(1999);
        assertThat((List<?>) JsonPath.read(json.getResponse().getContentAsString(), "$.rows")).isEmpty();
        assertThat((Integer) JsonPath.read(json.getResponse().getContentAsString(), "$.summary.rows"))
                .isZero();

        MvcResult csv = export(session, "1999", "csv");
        assertThat(csv.getResponse().getContentType()).contains("text/csv");
        String header = String.join(";", ElsterCsvWriter.header()) + "\n";
        assertThat(csv.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(header);
    }

    @Test
    @DisplayName("private vehicle use is an income (line 20, Kz 106) and never part of the expenses")
    void privateVehicleUseIsIncomeNotExpense() throws Exception {
        Session session = register();
        UUID privateUse = document(session, "fahrzeug.privatnutzung", date(2026, 5, 10), null);
        extraction(session, privateUse, "Netto", "100,00");
        extraction(session, privateUse, "Umsatzsteuer", "19,00");
        extraction(session, privateUse, "Brutto", "119,00");
        UUID material = document(session, "material.waren", date(2026, 6, 1), null);
        invoice(session, material, "100.00", "19.00", "119.00", date(2026, 6, 1));

        String body = export(session, "2026", null).getResponse().getContentAsString();

        assertThat((String) JsonPath.read(body, "$.rows[0].bookingKind")).isEqualTo("EINNAHME");
        assertThat((String) JsonPath.read(body, "$.rows[0].euerLine")).isEqualTo("20");
        assertThat((String) JsonPath.read(body, "$.rows[0].euerKz")).isEqualTo("106");
        assertThat((Double) JsonPath.read(body, "$.rows[0].netto")).isEqualTo(100.00);
        assertThat((String) JsonPath.read(body, "$.rows[1].bookingKind")).isEqualTo("AUSGABE");
        assertThat((Double) JsonPath.read(body, "$.summary.expensesNetto")).isEqualTo(100.00);
        assertThat((Double) JsonPath.read(body, "$.summary.incomeNetto")).isEqualTo(100.00);
        assertThat((Double) JsonPath.read(body, "$.summary.netto")).isEqualTo(200.00);
    }

    @Test
    @DisplayName("Bewirtung is split into 70 % deductible (Kz 175) and 30 % not deductible (Kz 165)")
    void entertainmentIsSplit70to30() throws Exception {
        Session session = register();
        UUID id = document(session, "betriebsausgaben.bewirtung", date(2026, 4, 2), null);
        invoice(session, id, "100.00", "19.00", "119.00", date(2026, 4, 2));

        String body = export(session, "2026", null).getResponse().getContentAsString();

        assertThat((String) JsonPath.read(body, "$.rows[0].euerLine")).isEqualTo("63");
        assertThat((String) JsonPath.read(body, "$.rows[0].euerKz")).isEqualTo("165/175");
        assertThat((Double) JsonPath.read(body, "$.rows[0].bewirtungAbziehbar70")).isEqualTo(70.00);
        assertThat((Double) JsonPath.read(body, "$.rows[0].bewirtungNichtAbziehbar30")).isEqualTo(30.00);
        // The input tax is deductible in full even on the 30 % that is not (EÜR line 57 / USt-VA Kz 66).
        assertThat((Boolean) JsonPath.read(body, "$.rows[0].vorsteuerAbziehbar")).isTrue();
        assertThat((Double) JsonPath.read(body, "$.summary.vorsteuerAbziehbar")).isEqualTo(19.00);
    }

    @Test
    @DisplayName("a Kleinunternehmer exports gross amounts, no VAT and no input tax")
    void kleinunternehmerHasNoVat() throws Exception {
        Session session = register();
        setting(session, "tax.kleinunternehmer", "true", "BOOLEAN");
        UUID id = document(session, "material.waren", date(2026, 2, 3), null);
        extraction(session, id, "Netto", "100,00");
        extraction(session, id, "Umsatzsteuer", "19,00");
        extraction(session, id, "Brutto", "119,00");

        String body = export(session, "2026", null).getResponse().getContentAsString();

        assertThat((Boolean) JsonPath.read(body, "$.settings.kleinunternehmer")).isTrue();
        assertThat((Double) JsonPath.read(body, "$.rows[0].netto")).isEqualTo(119.00);
        assertThat((Double) JsonPath.read(body, "$.rows[0].ust")).isEqualTo(0.00);
        assertThat((Double) JsonPath.read(body, "$.rows[0].brutto")).isEqualTo(119.00);
        assertThat((Boolean) JsonPath.read(body, "$.rows[0].vorsteuerAbziehbar")).isFalse();
        assertThat((Double) JsonPath.read(body, "$.summary.ust")).isEqualTo(0.00);
        assertThat((Double) JsonPath.read(body, "$.summary.expensesNetto")).isEqualTo(119.00);
    }

    @Test
    @DisplayName("the CSV has the promised columns, German decimals and the Soll/Haben direction")
    void csvHasThePromisedColumns() throws Exception {
        Session session = register();
        UUID id = document(session, "material.waren", date(2026, 3, 4), null);
        invoice(session, id, "100.00", "19.00", "119.00", date(2026, 3, 4));

        MvcResult result = export(session, "2026", "csv");
        String[] lines = result.getResponse().getContentAsString(StandardCharsets.UTF_8).split("\n");

        assertThat(lines[0]).isEqualTo("category_code;euer_line;euer_kz;datev_skr03;datev_skr04;booking_kind;"
                + "netto;ust;brutto;vorsteuer_abziehbar;bewirtung_abziehbar_70;"
                + "bewirtung_nicht_abziehbar_30;document_id;date");
        assertThat(lines[1]).isEqualTo("material.waren;27;100;3400;5400;AUSGABE;100,00;19,00;119,00;true;"
                + "0,00;0,00;" + id + ";2026-03-04");
    }

    @Test
    @DisplayName("gifts above 50 € are exported with a warning, per receipt and per recipient and year")
    void giftsOverTheLimitAreWarnedAbout() throws Exception {
        Session session = register();
        UUID first = document(session, "betriebsausgaben.geschenke", date(2026, 11, 20), "Muster GmbH");
        extraction(session, first, "Brutto", "30,00");
        UUID second = document(session, "betriebsausgaben.geschenke", date(2026, 12, 1), "Muster GmbH");
        extraction(session, second, "Brutto", "30,00");
        UUID big = document(session, "betriebsausgaben.geschenke", date(2026, 12, 5), "Andere AG");
        extraction(session, big, "Brutto", "60,00");

        String body = export(session, "2026", null).getResponse().getContentAsString();

        List<String> codes = JsonPath.read(body, "$.summary.warnings[*].code");
        assertThat(codes).contains("GIFT_OVER_50_RECIPIENT", "GIFT_OVER_50_SINGLE");
        // A warning is not a blocker: all three receipts are rows of the export.
        assertThat((List<?>) JsonPath.read(body, "$.rows")).hasSize(3);
        assertThat((String) JsonPath.read(body, "$.rows[2].euerLine")).isEqualTo("62");
    }

    @Test
    @DisplayName("without a year parameter the configured Wirtschaftsjahr of the tenant is used")
    void wirtschaftsjahrFromSettingsIsTheDefault() throws Exception {
        Session session = register();
        setting(session, "tax.wirtschaftsjahr", "2025", "NUMBER");
        document(session, "material.waren", date(2025, 7, 1), null);

        String body = export(session, null, null).getResponse().getContentAsString();

        assertThat((Integer) JsonPath.read(body, "$.year")).isEqualTo(2025);
        assertThat((List<?>) JsonPath.read(body, "$.rows")).hasSize(1);
    }

    @Test
    @DisplayName("a receipt without a document date is exported in the year it arrived")
    void documentWithoutDateIsExportedInItsArrivalYear() throws Exception {
        Session session = register();
        UUID id = UUID.randomUUID();
        String hash = (id.toString() + UUID.randomUUID()).replace("-", "");
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status, document_date,
                                       received_at, expense_category_id, expense_category_source, categorized_at)
                values (?, ?, 'kassenbon.jpg', 'kassenbon.jpg', 'image/jpeg', 100, ?, 'UPLOAD', 'READY',
                        'NOT_REQUIRED', null, timestamptz '2026-03-15 10:00:00+00',
                        (select id from expense_categories where code = 'werkzeuge.verbrauchsmaterial_werkzeug'),
                        'RULE', now())
                """, id, session.tenantId(), hash));
        extraction(session, id, "Betrag", "27,24");

        String body = export(session, "2026", null).getResponse().getContentAsString();

        assertThat((List<?>) JsonPath.read(body, "$.rows")).hasSize(1);
        assertThat((String) JsonPath.read(body, "$.rows[0].documentId")).isEqualTo(id.toString());
        assertThat((String) JsonPath.read(body, "$.rows[0].date")).isEqualTo("2026-03-15");
        assertThat((String) JsonPath.read(body, "$.rows[0].euerLine")).isEqualTo("51");
        List<String> codes = JsonPath.read(body, "$.summary.warnings[*].code");
        assertThat(codes).contains("DOCUMENT_DATE_MISSING");
    }
}

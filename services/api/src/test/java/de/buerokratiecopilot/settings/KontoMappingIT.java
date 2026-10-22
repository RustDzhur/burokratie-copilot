package de.buerokratiecopilot.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.exports.DatevExportService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The account mapping behind the DATEV export (Kanzlei edition, §5.6, §8.4), through HTTP and a real PostgreSQL.
 *
 * <p><strong>The defect this test protects against.</strong> {@code konto_mapping} was created by migration V79
 * and read by {@link DatevExportService}, but there was no way to write a row — no API, no screen. Every voucher
 * was skipped with "Kein Konto-Mapping" and an export produced a file with no bookings. The cases below are the
 * ones a mock would hide: the CSV import resolves clients by name and names the lines it skips, validation
 * refuses an unusable row instead of storing it, every change reaches the audit trail, and the row level
 * security policy keeps another account from seeing or changing anything.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KontoMappingIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private record Account(Cookie cookie, UUID tenantId, UUID userId) {
    }

    // ------------------------------------------------------------------ setup

    private Account register() throws Exception {
        String email = "konto-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Konten Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(body(registration), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(body(registration), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Account(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private UUID contact(Account account, String name) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (tenant_id, contact_type, display_name)
                values (?, 'ORGANIZATION', ?)
                returning id
                """, UUID.class, account.tenantId(), name));
    }

    // ------------------------------------------------------------------ calls

    private MvcResult call(Account account, MockHttpServletRequestBuilder request, String json) throws Exception {
        return mockMvc.perform(request.with(csrf()).cookie(account.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private MvcResult create(Account account, UUID mandantId, String supplier, String category,
                             String sollkonto, String habenkonto, String steuerschluessel) throws Exception {
        String mandant = mandantId == null ? "null" : "\"" + mandantId + "\"";
        return call(account, post("/api/v1/settings/konto-mapping"), """
                {"mandantId":%s,"supplier":%s,"category":%s,"sollkonto":%s,"habenkonto":%s,
                 "steuerschluessel":%s}
                """.formatted(mandant, quote(supplier), quote(category), quote(sollkonto),
                quote(habenkonto), quote(steuerschluessel)));
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private List<Map<String, Object>> items(Account account, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/settings/konto-mapping" + query)
                        .cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(body(result), "$.items");
    }

    private MvcResult importCsv(Account account, String csv) throws Exception {
        return mockMvc.perform(multipart("/api/v1/settings/konto-mapping/import")
                        .file(new MockMultipartFile("file", "konten.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .with(csrf()).cookie(account.cookie()))
                .andReturn();
    }

    private int auditCount(Account account) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from audit_events
                 where tenant_id = ? and action = 'KONTO_MAPPING_CHANGED'
                """, Integer.class, account.tenantId()));
    }

    // ------------------------------------------------------------------ CRUD

    @Test
    @DisplayName("a mapping is created, listed, filtered, changed and deleted — and every step is audited")
    void crudAndAudit() throws Exception {
        Account account = register();
        UUID mandant = contact(account, "Mandant A GmbH");
        UUID other = contact(account, "Mandant B GmbH");

        MvcResult created = create(account, mandant, "  ACME GmbH  ", "finance", " 1200 ", "1600", "");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> createdBody = JsonPath.read(body(created), "$");
        String id = (String) createdBody.get("id");
        assertThat(createdBody.get("mandantId")).isEqualTo(mandant.toString());
        assertThat(createdBody.get("mandantName")).isEqualTo("Mandant A GmbH");
        assertThat(createdBody.get("supplier")).as("trimmed").isEqualTo("ACME GmbH");
        assertThat(createdBody.get("category")).as("one spelling").isEqualTo("FINANCE");
        assertThat(createdBody.get("sollkonto")).isEqualTo("1200");
        assertThat(createdBody.get("habenkonto")).isEqualTo("1600");
        // Spring writes only values that exist, so a cleared field is absent rather than an explicit null.
        assertThat(createdBody.get("steuerschluessel")).isNull();

        // A second, account-wide row, so the filters have something to distinguish.
        assertThat(create(account, null, null, null, "1000", "1200", "19").getResponse().getStatus())
                .isEqualTo(201);

        assertThat(items(account, "")).hasSize(2);
        assertThat(items(account, "?mandantId=" + mandant))
                .extracting(row -> row.get("supplier")).containsExactly("ACME GmbH");
        assertThat(items(account, "?supplier=acme"))
                .as("supplier search is a case-insensitive part of the name")
                .extracting(row -> row.get("supplier")).containsExactly("ACME GmbH");
        assertThat(items(account, "?category=finance"))
                .extracting(row -> row.get("category")).containsExactly("FINANCE");
        assertThat(items(account, "?supplier=nothing")).isEmpty();

        MvcResult listed = mockMvc.perform(get("/api/v1/settings/konto-mapping").cookie(account.cookie()))
                .andExpect(status().isOk()).andReturn();
        assertThat((Integer) JsonPath.read(body(listed), "$.count")).isEqualTo(2);
        assertThat((List<String>) JsonPath.read(body(listed), "$.categories"))
                .as("the dialog offers the categories of the document taxonomy")
                .contains("FINANCE");

        MvcResult patched = call(account, patch("/api/v1/settings/konto-mapping/{id}", id), """
                {"mandantId":null,"supplier":null,"category":"GOVERNMENT","sollkonto":"1300",
                 "habenkonto":"1600","steuerschluessel":"21"}
                """);
        assertThat(patched.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> patchedBody = JsonPath.read(body(patched), "$");
        assertThat(patchedBody.get("mandantId")).as("an emptied client clears the dimension").isNull();
        assertThat(patchedBody.get("supplier")).isNull();
        assertThat(patchedBody.get("category")).isEqualTo("GOVERNMENT");
        assertThat(patchedBody.get("sollkonto")).isEqualTo("1300");
        assertThat(items(account, "?mandantId=" + other)).isEmpty();

        assertThat(mockMvc.perform(delete("/api/v1/settings/konto-mapping/{id}", id)
                .with(csrf()).cookie(account.cookie())).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(items(account, "")).hasSize(1);
        assertThat(mockMvc.perform(delete("/api/v1/settings/konto-mapping/{id}", id)
                .with(csrf()).cookie(account.cookie())).andReturn().getResponse().getStatus()).isEqualTo(404);

        assertThat(auditCount(account)).as("create, create, update, delete").isEqualTo(4);
        List<Map<String, Object>> audits = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForList("""
                select description, changes, entity_label
                  from audit_events
                 where tenant_id = ? and action = 'KONTO_MAPPING_CHANGED'
                 order by created_at
                """, account.tenantId()));
        assertThat(audits).extracting(row -> String.valueOf(row.get("description")))
                .anyMatch(text -> text.contains("angelegt"))
                .anyMatch(text -> text.contains("geändert"))
                .anyMatch(text -> text.contains("gelöscht"));
        assertThat(audits).anySatisfy(row -> assertThat(String.valueOf(row.get("changes")))
                .contains("sollkonto").contains("habenkonto"));
    }

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("an unusable mapping is refused with a sentence instead of being stored")
    void validation() throws Exception {
        Account account = register();
        UUID mandant = contact(account, "Mandant Prüfung");

        assertThat(create(account, mandant, null, null, null, "1600", null).getResponse().getStatus())
                .as("Sollkonto is mandatory").isEqualTo(400);
        assertThat(create(account, mandant, null, null, "1200", "  ", null).getResponse().getStatus())
                .as("a blank Habenkonto is not a mapping").isEqualTo(400);
        assertThat(create(account, mandant, null, null, "1".repeat(21), "1600", null)
                .getResponse().getStatus()).as("account columns are 20 chars").isEqualTo(400);
        assertThat(create(account, UUID.randomUUID(), null, null, "1200", "1600", null)
                .getResponse().getStatus()).as("an unknown client is a 400, not a failed constraint")
                .isEqualTo(400);
        assertThat(create(account, mandant, "x".repeat(401), null, "1200", "1600", null)
                .getResponse().getStatus()).isEqualTo(400);

        MvcResult first = create(account, mandant, "ACME", "FINANCE", "1200", "1600", "21");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String id = JsonPath.read(body(first), "$.id");

        MvcResult duplicate = create(account, mandant, "ACME", "FINANCE", "1400", "1600", "21");
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(body(duplicate), "$.code")).isEqualTo("DUPLICATE_RESOURCE");

        assertThat(call(account, patch("/api/v1/settings/konto-mapping/{id}", UUID.randomUUID()),
                "{\"sollkonto\":\"1200\",\"habenkonto\":\"1600\"}").getResponse().getStatus())
                .as("an unknown row is not found").isEqualTo(404);

        mockMvc.perform(get("/api/v1/settings/konto-mapping")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/settings/konto-mapping").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sollkonto\":\"1200\",\"habenkonto\":\"1600\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(id).isNotBlank();
    }

    // ------------------------------------------------------------------ import

    @Test
    @DisplayName("a CSV import writes what it can, updates changed rows and names every skipped line")
    void csvImport() throws Exception {
        Account account = register();
        UUID mandant = contact(account, "Mandant CSV");

        String csv = """
                mandant;lieferant;kategorie;sollkonto;habenkonto;steuerschluessel
                Mandant CSV;ACME GmbH;FINANCE;1200;1600;21
                ;;;1000;1200;
                Unbekannt GmbH;Niemand;FINANCE;1200;1600;
                Mandant CSV;ACME GmbH;FINANCE;1300;1600;21
                """;

        MvcResult imported = importCsv(account, csv);
        assertThat(imported.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(imported), "$.created")).as("two new rows").isEqualTo(2);
        assertThat((Integer) JsonPath.read(body(imported), "$.updated"))
                .as("the fourth line changed the first row's account").isEqualTo(1);
        List<Map<String, Object>> skipped = JsonPath.read(body(imported), "$.skipped");
        assertThat(skipped).hasSize(1);
        assertThat(skipped.getFirst().get("line")).isEqualTo(4);
        assertThat(String.valueOf(skipped.getFirst().get("reason")))
                .contains("Unbekannter Mandant").contains("Unbekannt GmbH");

        List<Map<String, Object>> rows = items(account, "");
        assertThat(rows).hasSize(2);
        Map<String, Object> acme = rows.stream()
                .filter(row -> "ACME GmbH".equals(row.get("supplier"))).findFirst().orElseThrow();
        assertThat(acme.get("sollkonto")).as("the later line wins").isEqualTo("1300");
        assertThat(acme.get("mandantName")).isEqualTo("Mandant CSV");
        Map<String, Object> general = rows.stream()
                .filter(row -> row.get("supplier") == null).findFirst().orElseThrow();
        assertThat(general.get("sollkonto")).isEqualTo("1000");
        assertThat(general.get("mandantId")).isNull();

        // The same rows again, with the values the first file ended on: nothing may change.
        MvcResult again = importCsv(account, """
                mandant;lieferant;kategorie;sollkonto;habenkonto;steuerschluessel
                Mandant CSV;ACME GmbH;FINANCE;1300;1600;21
                ;;;1000;1200;
                Unbekannt GmbH;Niemand;FINANCE;1200;1600;
                """);
        assertThat((Integer) JsonPath.read(body(again), "$.created")).isZero();
        assertThat((Integer) JsonPath.read(body(again), "$.updated")).as("nothing changed this time").isZero();
        List<Map<String, Object>> skippedAgain = JsonPath.read(body(again), "$.skipped");
        assertThat(skippedAgain).hasSize(3);
        assertThat(skippedAgain.stream().map(row -> String.valueOf(row.get("reason"))).toList())
                .anyMatch(reason -> reason.contains("Unverändert"))
                .anyMatch(reason -> reason.contains("Unbekannter Mandant"));

        assertThat(items(account, "")).as("a repeated import must not duplicate rows").hasSize(2);

        // A quoted supplier survives the semicolon that Excel would otherwise treat as a column.
        MvcResult quoted = importCsv(account, """
                mandant;lieferant;kategorie;sollkonto;habenkonto;steuerschluessel
                ;"ACME; Handels GmbH";;1200;1600;
                """);
        assertThat(quoted.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(quoted), "$.created")).isEqualTo(1);
        assertThat(items(account, "?supplier=handels"))
                .extracting(row -> row.get("supplier")).containsExactly("ACME; Handels GmbH");

        assertThat(auditCount(account)).as("two created, one updated, one quoted row created").isEqualTo(4);
        List<Map<String, Object>> audits = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForList("""
                select changes from audit_events
                 where tenant_id = ? and action = 'KONTO_MAPPING_CHANGED'
                """, account.tenantId()));
        assertThat(audits).anySatisfy(row -> assertThat(String.valueOf(row.get("changes")))
                .contains("CSV").contains("line"));
    }

    @Test
    @DisplayName("a file with the wrong header is refused as a whole, and an empty file too")
    void importRefusesUnreadableFiles() throws Exception {
        Account account = register();

        MvcResult wrongHeader = importCsv(account, """
                mandant;lieferant;sollkonto;habenkonto
                Mandant;ACME;1200;1600
                """);
        assertThat(wrongHeader.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(body(wrongHeader), "$.message"))
                .contains("Kopfzeile").contains("kategorie");

        MvcResult empty = importCsv(account, "");
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);
        assertThat(items(account, "")).isEmpty();
        assertThat(auditCount(account)).isZero();
    }

    // ------------------------------------------------------------------ the reason all of this exists

    @Test
    @DisplayName("a mapping created through this API makes the DATEV export produce a booking line")
    void aMappingMakesTheExportProduceALine() throws Exception {
        Account account = register();
        UUID mandant = contact(account, "Mandant Ausfuhr");
        UUID document = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into documents (tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status,
                                       beleg_status, mandant_id, document_date, document_number, sender)
                values (?, 'beleg.pdf', 'beleg.pdf', 'application/pdf', 10, ?, 'UPLOAD', 'READY',
                        'NOT_REQUIRED', 'ZU_PRUEFEN', ?, ?, ?, ?)
                returning id
                """, UUID.class, account.tenantId(),
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()),
                mandant, LocalDate.of(2026, 1, 15), "RE-2026-101", "ACME GmbH"));

        // Before the mapping exists the export has to skip the voucher: this is the defect the screen closes.
        MvcResult withoutMapping = mockMvc.perform(post("/api/v1/exports/datev").with(csrf())
                        .cookie(account.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodFrom\":\"2026-01-01\",\"periodTo\":\"2026-01-31\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat((Integer) JsonPath.read(body(withoutMapping), "$.belegCount")).isZero();
        assertThat((List<String>) JsonPath.read(body(withoutMapping), "$.skipped[*].reason"))
                .contains(DatevExportService.REASON_NO_KONTO_MAPPING);

        assertThat(create(account, mandant, "ACME GmbH", null, "1200", "1600", "21")
                .getResponse().getStatus()).isEqualTo(201);

        MvcResult exported = mockMvc.perform(post("/api/v1/exports/datev").with(csrf())
                        .cookie(account.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodFrom\":\"2026-01-01\",\"periodTo\":\"2026-01-31\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat((Integer) JsonPath.read(body(exported), "$.belegCount")).isEqualTo(1);
        String batchId = JsonPath.read(body(exported), "$.id");

        String csv = new String(mockMvc.perform(get("/api/v1/exports/datev/{id}/download", batchId)
                        .cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).contains("RE-2026-101").contains(";1200;1600;21");
        assertThat(RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject(
                "select beleg_status from documents where id = ?", String.class, document)))
                .isEqualTo("EXPORTIERT");
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("another account neither sees nor changes the mapping, and cannot attach its rows to a foreign client")
    void anotherAccountIsIsolated() throws Exception {
        Account owner = register();
        UUID mandant = contact(owner, "Mandant Geheim");
        String id = JsonPath.read(body(create(owner, mandant, "ACME", "FINANCE", "1200", "1600", "21")),
                "$.id");

        Account stranger = register();
        assertThat(items(stranger, "")).isEmpty();

        // An identifier of another account is not found, not forbidden: the answer must not confirm it exists.
        assertThat(call(stranger, patch("/api/v1/settings/konto-mapping/{id}", id), """
                {"mandantId":null,"supplier":null,"category":null,"sollkonto":"9999","habenkonto":"9999",
                 "steuerschluessel":null}
                """).getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(delete("/api/v1/settings/konto-mapping/{id}", id)
                .with(csrf()).cookie(stranger.cookie())).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(create(stranger, mandant, null, null, "1200", "1600", null).getResponse().getStatus())
                .as("a foreign client identifier is refused by the second guard").isEqualTo(400);

        // The CSV import resolves clients inside the caller's account: the owner's client is unknown here.
        MvcResult imported = importCsv(stranger, """
                mandant;lieferant;kategorie;sollkonto;habenkonto;steuerschluessel
                Mandant Geheim;ACME;FINANCE;1200;1600;
                """);
        assertThat((Integer) JsonPath.read(body(imported), "$.created")).isZero();
        Object reason = JsonPath.read(body(imported), "$.skipped[0].reason");
        assertThat(String.valueOf(reason)).contains("Unbekannter Mandant");

        // Row Level Security is the second guard: even a query written without the tenant filter sees nothing.
        UUID caseId = UUID.fromString(id);
        assertThat(RequestContext.runAs(stranger.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from konto_mapping where id = ?", Integer.class, caseId))).isZero();
        assertThat(RequestContext.runAs(owner.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from konto_mapping where id = ?", Integer.class, caseId))).isEqualTo(1);
        assertThat(items(owner, "")).hasSize(1);
    }
}

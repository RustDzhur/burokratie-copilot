package de.buerokratiecopilot.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The DATEV export from the database to the downloaded file (Kanzlei edition, §5.6, §11.7).
 *
 * <p>The cases protected here are the ones a screen alone cannot protect: a voucher without a client must not
 * appear in the file even when it is in the period; a voucher without an account mapping must not appear; the
 * successful ones move to {@code EXPORTIERT} and are not exported twice; and a second account sees neither the
 * package nor its file. The CSV is downloaded and its bytes are read, so the assertions are about the file the
 * office receives, not about a byte array's length.</p>
 *
 * <p><strong>The format is provisional.</strong> The test pins the warning line precisely because the profile
 * must not silently start looking final before Appendix 1 is agreed with a Kanzlei.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatevExportIT {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate JAN_31 = LocalDate.of(2026, 1, 31);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private record Account(Cookie cookie, UUID tenantId, UUID userId) {
    }

    // ------------------------------------------------------------------ setup

    private Account register() throws Exception {
        String email = "datev-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"DATEV Test","accountType":"COMPANY"}
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

    private UUID document(Account account, UUID mandantId, LocalDate date, String number, String sender,
                          String amount) {
        return RequestContext.runAs(account.tenantId(), () -> {
            UUID id = jdbc.queryForObject("""
                    insert into documents (tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                           content_hash, source, processing_status, review_status,
                                           beleg_status, mandant_id, document_date, document_number, sender)
                    values (?, 'beleg.pdf', 'beleg.pdf', 'application/pdf', 10, ?, 'UPLOAD', 'READY',
                            'NOT_REQUIRED', 'ZU_PRUEFEN', ?, ?, ?, ?)
                    returning id
                    """, UUID.class, account.tenantId(),
                    "%064x".formatted(ThreadLocalRandom.current().nextLong()),
                    mandantId, date, number, sender);
            if (amount != null) {
                jdbc.update("""
                        insert into document_extractions (document_id, field_key, field_value, normalized_value,
                                                          value_type, source_kind, source_snippet, extractor)
                        values (?, 'Brutto', ?, ?, 'AMOUNT', 'TEXT', 'Gesamtbetrag laut Rechnung', 'RULE')
                        """, id, amount + " EUR", amount);
            }
            return id;
        });
    }

    private void mapping(Account account, UUID mandantId, String supplier, String category, String sollkonto,
                         String habenkonto, String steuerschluessel) {
        RequestContext.runAs(account.tenantId(), () -> jdbc.update("""
                insert into konto_mapping (tenant_id, mandant_id, supplier, category, sollkonto, habenkonto,
                                           steuerschluessel)
                values (?, ?, ?, ?, ?, ?, ?)
                """, account.tenantId(), mandantId, supplier, category, sollkonto, habenkonto,
                steuerschluessel));
    }

    // ------------------------------------------------------------------ calls

    private MvcResult export(Account account, LocalDate from, LocalDate to, UUID mandantId) throws Exception {
        String mandant = mandantId == null ? "null" : "\"" + mandantId + "\"";
        return mockMvc.perform(post("/api/v1/exports/datev").with(csrf()).cookie(account.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"periodFrom":"%s","periodTo":"%s","mandantId":%s}
                                """.formatted(from, to, mandant)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult download(Account account, UUID batchId) throws Exception {
        return mockMvc.perform(get("/api/v1/exports/datev/{id}/download", batchId).cookie(account.cookie()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment")))
                .andReturn();
    }

    private String belegStatus(Account account, UUID documentId) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select beleg_status from documents where id = ? and tenant_id = ?
                """, String.class, documentId, account.tenantId()));
    }

    private static UUID batchId(MvcResult result) {
        return UUID.fromString(JsonPath.read(body(result), "$.id"));
    }

    private static int belegCount(MvcResult result) {
        return JsonPath.read(body(result), "$.belegCount");
    }

    private static BigDecimal totalAmount(MvcResult result) {
        return new BigDecimal(JsonPath.read(body(result), "$.totalAmount").toString());
    }

    private static Map<UUID, String> skippedReasons(MvcResult result) {
        List<Map<String, Object>> skipped = JsonPath.read(body(result), "$.skipped");
        return skipped.stream().collect(Collectors.toMap(
                row -> UUID.fromString(String.valueOf(row.get("documentId"))),
                row -> String.valueOf(row.get("reason"))));
    }

    // ------------------------------------------------------------------ the file

    @Test
    @DisplayName("only vouchers with a client and an account mapping reach the file; the rest are named as skipped")
    void onlyCompleteVouchersReachTheFile() throws Exception {
        Account account = register();
        UUID mandantA = contact(account, "Mandant A GmbH");
        UUID mandantB = contact(account, "Mandant B GmbH");
        mapping(account, mandantA, null, null, "1200", "1600", "21");

        UUID exportedDocument = document(account, mandantA, LocalDate.of(2026, 1, 15),
                "RE-2026-001", "Lieferant & Co. KG", "119.00");
        UUID withoutMandant = document(account, null, LocalDate.of(2026, 1, 16),
                "RE-2026-002", "Niemand GmbH", "50.00");
        UUID withoutMapping = document(account, mandantB, LocalDate.of(2026, 1, 17),
                "RE-2026-003", "Anderer Lieferant", "75.00");

        MvcResult created = export(account, JAN_1, JAN_31, null);
        UUID batch = batchId(created);

        assertThat(belegCount(created)).isEqualTo(1);
        assertThat(totalAmount(created)).isEqualByComparingTo("119.00");
        assertThat(skippedReasons(created))
                .containsEntry(withoutMandant, DatevExportService.REASON_NO_MANDANT)
                .containsEntry(withoutMapping, DatevExportService.REASON_NO_KONTO_MAPPING)
                .hasSize(2);

        byte[] content = download(account, batch).getResponse().getContentAsByteArray();
        assertThat(content)
                .as("UTF-8 BOM so Excel opens the file without an import dialog")
                .startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(content, StandardCharsets.UTF_8);
        assertThat(csv)
                .as("the format is provisional and must say so")
                .contains(DatevCsvWriter.PROVISIONAL_NOTE)
                .contains("Belegnummer;Belegdatum;Mandant;Lieferant;Betrag;Währung;Sollkonto;Habenkonto;Steuerschlüssel")
                .contains("RE-2026-001;15.01.2026;Mandant A GmbH;Lieferant & Co. KG;119,00;EUR;1200;1600;21")
                .doesNotContain("RE-2026-002")
                .doesNotContain("RE-2026-003");

        assertThat(belegStatus(account, exportedDocument)).isEqualTo("EXPORTIERT");
        assertThat(belegStatus(account, withoutMandant))
                .as("a voucher without a client stays in the queue")
                .isEqualTo("ZU_PRUEFEN");
        assertThat(belegStatus(account, withoutMapping))
                .as("a voucher without an account mapping stays in the queue")
                .isEqualTo("ZU_PRUEFEN");

        List<Map<String, Object>> items = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForList("""
                select document_id, status, error_message
                  from export_batch_items
                 where batch_id = ? and tenant_id = ?
                 order by status, document_id
                """, batch, account.tenantId()));
        assertThat(items).hasSize(3);
        assertThat(items).filteredOn(row -> "OK".equals(row.get("status"))).hasSize(1);
        assertThat(items).filteredOn(row -> "SKIPPED".equals(row.get("status"))).hasSize(2);

        Map<String, Object> audit = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForMap("""
                select description, changes
                  from audit_events
                 where tenant_id = ? and action = 'DATEV_EXPORT_CREATED'
                 order by created_at desc
                 limit 1
                """, account.tenantId()));
        assertThat(String.valueOf(audit.get("description")))
                .contains("1 Belege")
                .contains("119.00");
        assertThat(String.valueOf(audit.get("changes")))
                .contains("belegCount")
                .contains("totalAmount");
    }

    @Test
    @DisplayName("a second export of the same period does not take the vouchers that already left")
    void exportedVouchersAreNotExportedAgain() throws Exception {
        Account account = register();
        UUID mandant = contact(account, "Mandant Wiederholung");
        mapping(account, mandant, null, null, "1200", "1600", null);
        UUID document = document(account, mandant, LocalDate.of(2026, 1, 20),
                "RE-2026-010", "Lieferant GmbH", "42.00");

        MvcResult first = export(account, JAN_1, JAN_31, null);
        assertThat(belegCount(first)).isEqualTo(1);
        assertThat(belegStatus(account, document)).isEqualTo("EXPORTIERT");

        MvcResult second = export(account, JAN_1, JAN_31, null);
        assertThat(belegCount(second)).isZero();
        assertThat(skippedReasons(second)).isEmpty();

        String secondCsv = new String(download(account, batchId(second)).getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(secondCsv).doesNotContain("RE-2026-010");
        assertThat(belegStatus(account, document)).isEqualTo("EXPORTIERT");
    }

    @Test
    @DisplayName("the period and the client filter select the vouchers; a later month is not included")
    void periodAndMandantFilter() throws Exception {
        Account account = register();
        UUID mandantA = contact(account, "Mandant Januar");
        UUID mandantB = contact(account, "Mandant Februar");
        mapping(account, mandantA, null, null, "1200", "1600", null);
        mapping(account, mandantB, null, null, "1200", "1600", null);

        document(account, mandantA, LocalDate.of(2026, 1, 10), "JA-2026-001", "A", "10.00");
        document(account, mandantB, LocalDate.of(2026, 1, 11), "JA-2026-002", "B", "20.00");
        UUID februaryA = document(account, mandantA, LocalDate.of(2026, 2, 10), "FE-2026-001", "A", "30.00");

        MvcResult january = export(account, JAN_1, JAN_31, null);
        String januaryCsv = new String(download(account, batchId(january)).getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(belegCount(january)).isEqualTo(2);
        assertThat(januaryCsv).contains("JA-2026-001").contains("JA-2026-002").doesNotContain("FE-2026-001");
        assertThat(belegStatus(account, februaryA)).isEqualTo("ZU_PRUEFEN");

        // A fresh month with a client filter: only the vouchers of that client are considered.
        UUID februaryOnly = document(account, mandantB, LocalDate.of(2026, 2, 11), "FE-2026-002", "B", "40.00");
        MvcResult filtered = export(account, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), mandantB);
        String filteredCsv = new String(download(account, batchId(filtered)).getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(belegCount(filtered)).isEqualTo(1);
        assertThat(filteredCsv).contains("FE-2026-002").doesNotContain("FE-2026-001");
        assertThat(belegStatus(account, februaryOnly)).isEqualTo("EXPORTIERT");
    }

    @Test
    @DisplayName("another account sees neither the package in the history nor its file")
    void anotherAccountSeesNothing() throws Exception {
        Account owner = register();
        UUID mandant = contact(owner, "Mandant RLS");
        mapping(owner, mandant, null, null, "1200", "1600", null);
        document(owner, mandant, LocalDate.of(2026, 1, 10), "RLS-2026-001", "Lieferant", "10.00");
        MvcResult created = export(owner, JAN_1, JAN_31, null);
        UUID batch = batchId(created);

        Account stranger = register();
        MvcResult history = mockMvc.perform(get("/api/v1/exports/datev").cookie(stranger.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat((List<?>) JsonPath.read(body(history), "$")).isEmpty();

        mockMvc.perform(get("/api/v1/exports/datev/{id}/download", batch).cookie(stranger.cookie()))
                .andExpect(status().isNotFound());

        // Passing a foreign client identifier is a validation error, not a way to export foreign vouchers.
        mockMvc.perform(post("/api/v1/exports/datev").with(csrf()).cookie(stranger.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"periodFrom":"2026-01-01","periodTo":"2026-01-31","mandantId":"%s"}
                                """.formatted(mandant)))
                .andExpect(status().isBadRequest());

        // Row Level Security is the second guard: even a query written without the tenant filter sees nothing.
        Integer visible = RequestContext.runAs(stranger.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from export_batches where id = ?
                """, Integer.class, batch));
        assertThat(visible).isZero();
    }

    @Test
    @DisplayName("a package without a client filter is stored as such and the history names who created it")
    void historyShowsThePackage() throws Exception {
        Account account = register();
        UUID mandant = contact(account, "Mandant Historie");
        mapping(account, mandant, null, null, "1200", "1600", null);
        document(account, mandant, LocalDate.of(2026, 3, 5), "HI-2026-001", "Lieferant", "99.50");

        MvcResult created = export(account, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null);
        MvcResult history = mockMvc.perform(get("/api/v1/exports/datev").cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn();

        List<Map<String, Object>> rows = JsonPath.read(body(history), "$");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("id")).isEqualTo(batchId(created).toString());
        assertThat(rows.getFirst().get("belegCount")).isEqualTo(1);
        assertThat(rows.getFirst().get("status")).isEqualTo("CREATED");
        assertThat(String.valueOf(rows.getFirst().get("filename"))).endsWith(".csv");
        assertThat(rows.getFirst().get("createdByName")).isEqualTo("DATEV Test");
    }

    @Test
    @DisplayName("a voucher without any date is skipped by name, not silently left out")
    void undatedVoucherIsReported() throws Exception {
        Account account = register();
        UUID mandant = contact(account, "Ohne Datum GmbH");
        mapping(account, mandant, null, null, "1200", "1600", null);

        // A letter the office never dated: it cannot be placed in any period, so the period query drops it.
        UUID undated = document(account, mandant, null, "OHNE-DATUM", "A", "10.00");
        document(account, mandant, LocalDate.of(2026, 3, 10), "MIT-DATUM", "A", "20.00");

        MvcResult batch = export(account, JAN_1, LocalDate.of(2026, 12, 31), null);

        // One voucher exported, and the undated one named with its reason - an empty skip list next to a short
        // batch is what a bookkeeper cannot explain.
        assertThat(belegCount(batch)).isEqualTo(1);
        assertThat(skippedReasons(batch)).containsEntry(undated, "Kein Belegdatum");
        assertThat(belegStatus(account, undated)).isEqualTo("ZU_PRUEFEN");
    }
}

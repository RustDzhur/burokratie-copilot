package de.buerokratiecopilot.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * The booking figures the Posteingang shows next to a voucher (Kanzlei edition, §5.1).
 *
 * <p>What is protected here is the honesty of the row: an amount, a voucher date, a supplier and the
 * next deadline come from what the extraction actually found, a document without those values shows
 * nothing rather than a zero, and a second cabinet sees neither the voucher nor its figures. The
 * deadline cases are the ones a queue gets wrong: an inactive or task-deleted deadline must not raise a
 * red badge, and among several open ones the earliest decides.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class DocumentBelegdatenIT {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BelegdatenService belegdaten;

    private record Account(Cookie cookie, UUID tenantId, UUID userId) {
    }

    // ------------------------------------------------------------------ fixtures

    private Account register(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Belegdaten Test","accountType":"COMPANY"}
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

    private UUID document(Account account, String filename, LocalDate documentDate) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into documents (tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status, beleg_status,
                                       document_date)
                values (?, ?, ?, 'application/pdf', 42, md5(random()::text) || md5(random()::text),
                        'UPLOAD', 'READY', 'NOT_REQUIRED', 'NEU', ?::date)
                returning id
                """, UUID.class, account.tenantId(), filename, filename, documentDate));
    }

    private void extraction(Account account, UUID documentId, String fieldKey, String value,
                            String normalized, String valueType) {
        RequestContext.runAs(account.tenantId(), () -> jdbc.update("""
                insert into document_extractions (document_id, field_key, field_value, normalized_value,
                                                  value_type, source_kind, source_snippet, extractor)
                values (?, ?, ?, ?, ?, 'TEXT', ?, 'RULE')
                """, documentId, fieldKey, value, normalized, valueType, value));
    }

    private UUID task(Account account, UUID documentId, boolean deleted) {
        // A Timestamp instead of an Instant: the JDBC driver binds java.time.Instant only with an explicit
        // SQL type, and a deleted task only needs "sometime before now".
        Object deletedAt = deleted ? java.sql.Timestamp.from(java.time.Instant.now()) : null;
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into tasks (tenant_id, title, source, source_document_id, deleted_at)
                values (?, 'Beleg prüfen', 'DOCUMENT', ?, ?)
                returning id
                """, UUID.class, account.tenantId(), documentId, deletedAt));
    }

    private void deadline(Account account, UUID taskId, UUID documentId, LocalDate dueDate, boolean active) {
        RequestContext.runAs(account.tenantId(), () -> jdbc.update("""
                insert into task_deadlines (tenant_id, task_id, deadline_type, due_date, source,
                                            source_document_id, is_active)
                values (?, ?, 'DOCUMENT_DATE', ?::date, 'DOCUMENT', ?, ?)
                """, account.tenantId(), taskId, dueDate, documentId, active));
    }

    // ------------------------------------------------------------------ calls

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private String list(Account account) throws Exception {
        return mockMvc.perform(get("/api/v1/documents").param("size", "100").cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("the queue row carries the extracted amount, voucher date and supplier, and the next deadline")
    void listCarriesTheExtractedBookingData() throws Exception {
        Account account = register("belegdaten");
        // The pipeline does not write documents.document_date; the date comes from the extracted field, the
        // same way the orchestrator reads it.
        UUID id = document(account, "rechnung.pdf", null);
        // The raw value keeps the way the invoice writes it; the normalised value is what the office books.
        extraction(account, id, "Brutto", "1.190,00 EUR", "1190.00", "AMOUNT");
        extraction(account, id, "Lieferant", "Muster GmbH", "Muster GmbH", "TEXT");
        extraction(account, id, "Rechnungsdatum", "15.03.2026", "2026-03-15", "DATE");
        LocalDate due = LocalDate.now(BERLIN).plusDays(2);
        deadline(account, task(account, id, false), id, due, true);

        String answer = list(account);
        assertThat((String) JsonPath.read(answer, "$.content[0].id")).isEqualTo(id.toString());
        assertThat(((Number) JsonPath.read(answer, "$.content[0].amount")).doubleValue()).isEqualTo(1190.00);
        // The currency is derived from the amount as written ("1.190,00 EUR"), so the screen can format
        // 1.234,56 € without the API inventing an amount or a currency that is not on the document.
        assertThat((String) JsonPath.read(answer, "$.content[0].amountCurrency")).isEqualTo("EUR");
        assertThat((String) JsonPath.read(answer, "$.content[0].documentDate")).isEqualTo("2026-03-15");
        assertThat((String) JsonPath.read(answer, "$.content[0].supplierName")).isEqualTo("Muster GmbH");
        assertThat((String) JsonPath.read(answer, "$.content[0].deadlineDate")).isEqualTo(due.toString());
        assertThat(((Number) JsonPath.read(answer, "$.content[0].deadlineDaysLeft")).intValue())
                .isEqualTo((int) ChronoUnit.DAYS.between(LocalDate.now(BERLIN), due));

        // The card answers exactly what the row does: a screen that opens a document must not have to
        // ask a second question to show the same figures.
        String card = mockMvc.perform(get("/api/v1/documents/{id}", id).cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(card, "$.amount")).doubleValue()).isEqualTo(1190.00);
        assertThat((String) JsonPath.read(card, "$.amountCurrency")).isEqualTo("EUR");
        assertThat((String) JsonPath.read(card, "$.documentDate")).isEqualTo("2026-03-15");
        assertThat((String) JsonPath.read(card, "$.supplierName")).isEqualTo("Muster GmbH");
        assertThat((String) JsonPath.read(card, "$.deadlineDate")).isEqualTo(due.toString());
    }

    @Test
    @DisplayName("a voucher without extracted values answers no fields instead of zeros and invents nothing")
    void emptyVoucherHasNoFigures() throws Exception {
        Account account = register("belegdaten-empty");
        UUID id = document(account, "leer.pdf", null);

        String answer = list(account);
        assertThat((String) JsonPath.read(answer, "$.content[0].id")).isEqualTo(id.toString());
        // The API omits absent values (Jackson non_null); the screen translates that into a dash. What
        // matters is that nothing is made up: no amount, no date, no supplier, no deadline.
        Map<String, Object> row = JsonPath.read(answer, "$.content[0]");
        assertThat(row).doesNotContainKeys("amount", "amountCurrency", "documentDate", "supplierName",
                "deadlineDate", "deadlineDaysLeft");

        String card = mockMvc.perform(get("/api/v1/documents/{id}", id).cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> detail = JsonPath.read(card, "$");
        assertThat(detail).doesNotContainKeys("amount", "amountCurrency", "documentDate", "supplierName",
                "deadlineDate", "deadlineDaysLeft");
    }

    @Test
    @DisplayName("the earliest active deadline wins; withdrawn and task-deleted deadlines never do")
    void earliestActiveDeadlineWins() throws Exception {
        Account account = register("belegdaten-deadlines");
        UUID id = document(account, "fristen.pdf", LocalDate.of(2026, 4, 1));
        LocalDate withdrawn = LocalDate.now(BERLIN).plusDays(1);
        LocalDate deletedTask = LocalDate.now(BERLIN).plusDays(2);
        LocalDate later = LocalDate.now(BERLIN).plusDays(30);
        LocalDate earliestOpen = LocalDate.now(BERLIN).plusDays(5);

        // A document has at most one open DOCUMENT task (a unique index says so), but that task may carry
        // several deadlines — which is exactly the case the query must resolve by date.
        UUID openTask = task(account, id, false);
        deadline(account, openTask, id, later, true);
        deadline(account, openTask, id, withdrawn, false);
        deadline(account, openTask, id, earliestOpen, true);
        deadline(account, task(account, id, true), id, deletedTask, true);

        String answer = list(account);
        assertThat((String) JsonPath.read(answer, "$.content[0].deadlineDate")).isEqualTo(earliestOpen.toString());
        assertThat(((Number) JsonPath.read(answer, "$.content[0].deadlineDaysLeft")).intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("an overdue deadline stays visible with a negative number of days left")
    void overdueDeadlineIsNotHidden() throws Exception {
        Account account = register("belegdaten-overdue");
        UUID id = document(account, "ueberfaellig.pdf", null);
        LocalDate due = LocalDate.now(BERLIN).minusDays(4);
        deadline(account, task(account, id, false), id, due, true);

        String answer = list(account);
        assertThat((String) JsonPath.read(answer, "$.content[0].deadlineDate")).isEqualTo(due.toString());
        // The card turns this into "überfällig"; the API states the fact (four days late), not the colour.
        assertThat(((Number) JsonPath.read(answer, "$.content[0].deadlineDaysLeft")).intValue()).isEqualTo(-4);
    }

    @Test
    @DisplayName("another cabinet sees neither the voucher nor the figures extracted from it")
    void anotherTenantSeesNothing() throws Exception {
        Account owner = register("belegdaten-owner");
        UUID id = document(owner, "vertraulich.pdf", LocalDate.of(2026, 5, 1));
        extraction(owner, id, "Brutto", "99,00 EUR", "99.00", "AMOUNT");
        deadline(owner, task(owner, id, false), id, LocalDate.now(BERLIN).plusDays(3), true);

        Account stranger = register("belegdaten-stranger");

        // The list of a fresh cabinet is empty, even though another cabinet holds a matching voucher.
        List<String> strangerSees = JsonPath.read(list(stranger), "$.content[*].id");
        assertThat(strangerSees).isEmpty();
        // Opening the foreign identifier is "not found", not an answer with the owner's figures.
        mockMvc.perform(get("/api/v1/documents/{id}", id).cookie(stranger.cookie()))
                .andExpect(status().isNotFound());

        // The enrichment query itself respects the tenant: called in the stranger's context it finds
        // nothing for the foreign document, even though its id is known.
        Map<UUID, BelegdatenService.Belegdaten> figures = RequestContext.runAs(stranger.tenantId(),
                () -> belegdaten.forDocuments(List.of(id)));
        assertThat(figures).isEmpty();

        // And the owner still sees the voucher, so the isolation is not a screen that hides everything.
        List<String> ownerSees = JsonPath.read(list(owner), "$.content[*].id");
        assertThat(ownerSees).containsExactly(id.toString());
    }
}

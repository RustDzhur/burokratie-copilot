package de.buerokratiecopilot.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * The Posteingang as a work queue: filters, search and bulk actions (Kanzlei edition, §5.1).
 *
 * <p>What these tests protect is the difference between a screen that looks filtered and a query that is:
 * the voucher status and the client filters must run in the database (including "Ohne Mandant", which is a
 * state and not a missing value), the search must find a word from inside the OCR text, and a bulk action
 * must go through the same status machine as the single-row buttons. A bulk action that silently ignored a
 * forbidden transition would look like success while the office believes the voucher moved.</p>
 *
 * <p>The tenant cases are part of the same contract: a second cabinet must see neither the vouchers nor the
 * clients of the first, and a batch must answer "not found" instead of reaching across the boundary.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class DocumentQueueIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private record Account(Cookie cookie, UUID tenantId, UUID userId) {
    }

    // ------------------------------------------------------------------ fixtures

    private Account register(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Kanzlei Test","accountType":"COMPANY"}
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

    private UUID mandant(Account account, String name) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (tenant_id, contact_type, display_name)
                values (?, 'ORGANIZATION', ?)
                returning id
                """, UUID.class, account.tenantId(), name));
    }

    /** A voucher as the pipeline would leave it: text read, no work done yet. */
    private UUID document(Account account, String filename, String belegStatus, UUID mandantId,
                          String extractedText) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into documents (tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status, beleg_status,
                                       mandant_id, extracted_text)
                values (?, ?, ?, 'application/pdf', 42,
                        md5(random()::text) || md5(random()::text), 'UPLOAD', 'READY', 'NOT_REQUIRED', ?, ?, ?)
                returning id
                """, UUID.class, account.tenantId(), filename, filename, belegStatus, mandantId, extractedText));
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** The identifiers of one page of the list, as the office sees them. */
    @SuppressWarnings("unchecked")
    private List<String> listed(Account account, String query) throws Exception {
        String answer = mockMvc.perform(get("/api/v1/documents" + query).cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(answer, "$.content[*].id");
    }

    private long total(Account account, String query) throws Exception {
        String answer = mockMvc.perform(get("/api/v1/documents" + query).cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number count = JsonPath.read(answer, "$.totalElements");
        return count.longValue();
    }

    private MvcResult batch(Account account, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/documents/batch")
                        .with(csrf()).cookie(account.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    // ------------------------------------------------------------------ filters

    @Test
    @DisplayName("the queue filters by voucher status and by client, and 'Ohne Mandant' is a filter of its own")
    void filtersByVoucherStatusAndClient() throws Exception {
        Account account = register("queue");
        UUID clientA = mandant(account, "Alpha GmbH");
        UUID clientB = mandant(account, "Beta AG");
        UUID fresh = document(account, "neu.pdf", "NEU", clientA, null);
        UUID open = document(account, "offen.pdf", "OFFEN", clientB, null);
        UUID unassigned = document(account, "ohne.pdf", "FEHLER", null, null);

        // No filter: the whole cabinet.
        assertThat(total(account, "")).isEqualTo(3);

        assertThat(listed(account, "?belegStatus=NEU")).containsExactly(fresh.toString());
        assertThat(listed(account, "?belegStatus=OFFEN&mandantId=" + clientB)).containsExactly(open.toString());
        // "Ohne Mandant" is a state the office works through, not the absence of a filter.
        assertThat(listed(account, "?mandantId=NONE")).containsExactly(unassigned.toString());
        assertThat(listed(account, "?mandantId=" + clientA)).containsExactly(fresh.toString());
        // A combination that matches nothing is empty, not "the filter fell back to everything".
        assertThat(total(account, "?belegStatus=EXPORTIERT&mandantId=" + clientA)).isZero();
        // The existing filters still work next to the new ones.
        assertThat(total(account, "?category=UNSORTED")).isEqualTo(3);
    }

    @Test
    @DisplayName("the search box searches the text OCR read from inside the document")
    void searchFindsAWordFromTheOcrText() throws Exception {
        Account account = register("ocr");
        UUID withWord = document(account, "scan.pdf", "NEU", null,
                "Die Rechnung enthält das Wort Zwickauerwasserstoff und sonst nichts Außergewöhnliches.");
        document(account, "other.pdf", "NEU", null, "Ein völlig anderer Beleg über Büromaterial.");

        // The whole word and a fragment of it: the queue is searched while the person remembers half of it.
        assertThat(listed(account, "?q=Zwickauerwasserstoff")).containsExactly(withWord.toString());
        assertThat(listed(account, "?q=wasserstoff")).containsExactly(withWord.toString());
        assertThat(total(account, "?q=GibtEsNicht")).isZero();
        // The old fields keep working: the file name still matches.
        assertThat(listed(account, "?q=other")).hasSize(1);
    }

    // ------------------------------------------------------------------ batches

    @Test
    @DisplayName("a batch ACCEPT moves only the vouchers the status machine allows and names the refusals")
    void batchAcceptsOnlyAllowedVouchers() throws Exception {
        Account account = register("batch-accept");
        UUID mandant = mandant(account, "Alpha GmbH");
        UUID fresh = document(account, "neu.pdf", "NEU", mandant, null);
        UUID exported = document(account, "exportiert.pdf", "EXPORTIERT", mandant, null);

        MvcResult result = batch(account, """
                {"ids":["%s","%s"],"action":"ACCEPT"}
                """.formatted(fresh, exported));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(body(result), "$.updated")).intValue()).isEqualTo(1);
        List<String> skippedIds = JsonPath.read(body(result), "$.skipped[*].id");
        assertThat(skippedIds).containsExactly(exported.toString());
        String reason = (String) JsonPath.read(body(result), "$.skipped[0].reason");
        assertThat(reason).contains("EXPORTIERT").contains("OFFEN");

        assertThat(requestedStatus(account, fresh)).isEqualTo("OFFEN");
        assertThat(requestedStatus(account, exported))
                .as("a refused voucher stays exactly where it was")
                .isEqualTo("EXPORTIERT");
        // Every change is audited, and only the change is: one voucher moved, one did not.
        assertThat(auditCount(account, fresh, "BELEG_STATUS_CHANGED")).isEqualTo(1);
        assertThat(auditCount(account, exported, "BELEG_STATUS_CHANGED")).isZero();
    }

    @Test
    @DisplayName("a batch ASSIGN_MANDANT assigns every voucher and audits each assignment")
    void batchAssignsClientAndWritesAudit() throws Exception {
        Account account = register("batch-mandant");
        UUID client = mandant(account, "Alpha GmbH");
        UUID first = document(account, "eins.pdf", "NEU", null, null);
        UUID second = document(account, "zwei.pdf", "NEU", null, null);

        MvcResult result = batch(account, """
                {"ids":["%s","%s"],"action":"ASSIGN_MANDANT","mandantId":"%s"}
                """.formatted(first, second, client));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(body(result), "$.updated")).intValue()).isEqualTo(2);
        assertThat((List<?>) JsonPath.read(body(result), "$.skipped")).isEmpty();
        assertThat(storedMandant(account, first)).isEqualTo(client);
        assertThat(storedMandant(account, second)).isEqualTo(client);
        assertThat(RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from audit_events
                 where tenant_id = ? and action = 'DOCUMENT_MANDANT_ASSIGNED'
                """, Integer.class, account.tenantId()))).isEqualTo(2);

        // Repeating the same assignment is not a second change: the answer says so instead of inventing one.
        MvcResult again = batch(account, """
                {"ids":["%s"],"action":"ASSIGN_MANDANT","mandantId":"%s"}
                """.formatted(first, client));
        assertThat(((Number) JsonPath.read(body(again), "$.updated")).intValue()).isZero();
        assertThat((String) JsonPath.read(body(again), "$.skipped[0].reason"))
                .contains("bereits").contains("zugeordnet");
    }

    @Test
    @DisplayName("more than 500 identifiers are refused with 400 before anything is touched")
    void refusesMoreThanFiveHundredIds() throws Exception {
        Account account = register("batch-limit");
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < DocumentBatchService.MAX_IDS + 1; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append('"').append(UUID.randomUUID()).append('"');
        }

        MvcResult result = batch(account, """
                {"ids":[%s],"action":"ACCEPT"}
                """.formatted(ids));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(body(result), "$.code")).isEqualTo("INVALID_REQUEST");
    }

    // ------------------------------------------------------------------ tenant boundary

    @Test
    @DisplayName("another cabinet sees neither the vouchers nor the clients, and a batch cannot move them")
    void anotherTenantIsInvisible() throws Exception {
        Account owner = register("owner");
        UUID client = mandant(owner, "Alpha GmbH");
        UUID foreignDocument = document(owner, "fremd.pdf", "NEU", null, "Vertraulicher Inhalt");

        Account stranger = register("stranger");

        // The list of a fresh cabinet is empty even though another cabinet holds matching vouchers.
        assertThat(total(stranger, "")).isZero();
        assertThat(total(stranger, "?mandantId=" + client)).isZero();
        assertThat(total(stranger, "?mandantId=NONE")).isZero();
        assertThat(total(stranger, "?q=Vertraulicher")).isZero();

        // A batch naming the foreign voucher answers "not found" and does not reach across the boundary.
        MvcResult batch = batch(stranger, """
                {"ids":["%s"],"action":"ACCEPT"}
                """.formatted(foreignDocument));
        assertThat(batch.getResponse().getStatus()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(body(batch), "$.updated")).intValue()).isZero();
        assertThat((String) JsonPath.read(body(batch), "$.skipped[0].reason"))
                .isEqualTo("Beleg nicht gefunden.");
        assertThat(requestedStatus(owner, foreignDocument))
                .as("the owner's voucher is untouched")
                .isEqualTo("NEU");

        // Assigning to a client of another cabinet is refused for the whole call: the foreign key alone would
        // accept the contact row, because PostgreSQL checks the target table without applying its policy.
        MvcResult stolen = batch(stranger, """
                {"ids":["%s"],"action":"ASSIGN_MANDANT","mandantId":"%s"}
                """.formatted(foreignDocument, client));
        assertThat(stolen.getResponse().getStatus()).isEqualTo(400);
        assertThat(storedMandant(owner, foreignDocument)).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private String requestedStatus(Account account, UUID documentId) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject(
                "select beleg_status from documents where id = ?", String.class, documentId));
    }

    private UUID storedMandant(Account account, UUID documentId) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject(
                "select mandant_id from documents where id = ?", UUID.class, documentId));
    }

    private int auditCount(Account account, UUID documentId, String action) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from audit_events
                 where tenant_id = ? and action = ? and entity_id = ?
                """, Integer.class, account.tenantId(), action, documentId));
    }
}

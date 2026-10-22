package de.buerokratiecopilot.mandanten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

import de.buerokratiecopilot.mandanten.MandantOverviewService.Health;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * The Mandanten screen from the database to the JSON (Kanzlei edition, §5.5).
 *
 * <p>The cases protected here are the ones a screen alone cannot protect: the counters must count exactly the
 * vouchers of that client and no others, the health rule must pick the more serious of two truths, a deadline
 * that has passed must not hide inside the "open" number, and a second account must see neither the list nor
 * the card. The empty cabinet is a case of its own - a fresh Kanzlei must get an empty list, not a row of
 * zeroes or an error, because the screen shows its first-client hint exactly then.</p>
 *
 * <p>The health rule is also pinned at its boundary here, where the fourteen days are arithmetic instead of
 * wall-clock time - a test that waits two weeks to see a status change is a test nobody runs.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class MandantOverviewIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private record Account(Cookie cookie, UUID tenantId, UUID userId) {
    }

    // ------------------------------------------------------------------ setup

    private Account register() throws Exception {
        String email = "mandant-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
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

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private UUID mandant(Account account, String name) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (tenant_id, contact_type, display_name)
                values (?, 'ORGANIZATION', ?)
                returning id
                """, UUID.class, account.tenantId(), name));
    }

    /** A voucher of a client. {@code lastMovementDaysAgo} null means "moved now". */
    private UUID document(Account account, UUID mandantId, String belegStatus, LocalDate documentDate,
                          Integer lastMovementDaysAgo) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into documents (tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status, beleg_status,
                                       mandant_id, document_date, created_at, updated_at)
                values (?, 'beleg.pdf', 'beleg.pdf', 'application/pdf', 10,
                        md5(random()::text) || md5(random()::text), 'UPLOAD', 'READY', 'NOT_REQUIRED', ?, ?,
                        ?, now() - make_interval(days => ?), now() - make_interval(days => ?))
                returning id
                """, UUID.class, account.tenantId(), belegStatus, mandantId, documentDate,
                lastMovementDaysAgo == null ? 0 : lastMovementDaysAgo,
                lastMovementDaysAgo == null ? 0 : lastMovementDaysAgo));
    }

    private void deadline(Account account, UUID documentId, LocalDate dueDate) {
        RequestContext.runAs(account.tenantId(), () -> {
            UUID taskId = jdbc.queryForObject("""
                    insert into tasks (tenant_id, title) values (?, 'Frist') returning id
                    """, UUID.class, account.tenantId());
            jdbc.update("""
                    insert into task_deadlines (tenant_id, task_id, deadline_type, due_date, source,
                                                source_document_id, is_active)
                    values (?, ?, 'DOCUMENT_DATE', ?, 'DOCUMENT', ?, true)
                    """, account.tenantId(), taskId, dueDate, documentId);
            return taskId;
        });
    }

    // ------------------------------------------------------------------ calls

    private List<Map<String, Object>> list(Account account, String query) throws Exception {
        String url = "/api/v1/mandanten" + (query == null ? "" : "?" + query);
        MvcResult result = mockMvc.perform(get(url).cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(body(result), "$");
    }

    private Map<String, Object> row(List<Map<String, Object>> rows, String displayName) {
        return rows.stream()
                .filter(row -> displayName.equals(row.get("displayName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Kein Mandant " + displayName + " in " + rows));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listField(Map<String, Object> row, String key) {
        return (List<Map<String, Object>>) row.get(key);
    }

    // ------------------------------------------------------------------ counters and health

    @Test
    @DisplayName("the counters count the vouchers of that client, and the health rule names who is in trouble")
    void countersAndHealth() throws Exception {
        Account account = register();
        UUID alpha = mandant(account, "Alpha GmbH");
        document(account, alpha, "OFFEN", LocalDate.now().minusDays(1), null);
        document(account, alpha, "ZU_PRUEFEN", LocalDate.now().minusDays(2), null);
        document(account, alpha, "EXPORTIERT", LocalDate.now().minusDays(3), null);
        document(account, alpha, "NEU", LocalDate.now().minusDays(4), null);

        UUID beta = mandant(account, "Beta GmbH");
        document(account, beta, "FEHLER", LocalDate.now().minusDays(1), null);

        UUID gamma = mandant(account, "Gamma GmbH");
        document(account, gamma, "EXPORTIERT", LocalDate.of(2026, 3, 5), null);

        // A voucher accepted but untouched for a month: "the work is waiting" is exactly this.
        UUID delta = mandant(account, "Delta GmbH");
        document(account, delta, "OFFEN", LocalDate.now().minusDays(40), 30);

        // A deadline that is already late: the client is exposed even though the voucher itself is accepted.
        UUID epsilon = mandant(account, "Epsilon GmbH");
        UUID epsilonDocument = document(account, epsilon, "OFFEN", LocalDate.now().minusDays(2), null);
        deadline(account, epsilonDocument, LocalDate.now().minusDays(1));

        // A deadline that is still ahead keeps the client OK, but shows in the "Fristen" column.
        UUID zeta = mandant(account, "Zeta GmbH");
        UUID zetaDocument = document(account, zeta, "OFFEN", LocalDate.now().minusDays(2), null);
        deadline(account, zetaDocument, LocalDate.now().plusDays(3));

        List<Map<String, Object>> rows = list(account, null);
        assertThat(rows).hasSize(6);

        Map<String, Object> alphaRow = row(rows, "Alpha GmbH");
        assertThat(alphaRow.get("documentsTotal")).isEqualTo(4);
        // "Offen" is everything still to be booked: the accepted voucher and the one nobody has opened yet.
        assertThat(alphaRow.get("offen")).isEqualTo(2);
        assertThat(alphaRow.get("zuPruefen")).isEqualTo(1);
        assertThat(alphaRow.get("exportiert")).isEqualTo(1);
        assertThat(alphaRow.get("fehler")).isEqualTo(0);
        assertThat(alphaRow.get("deadlinesOpen")).isEqualTo(0);
        assertThat(alphaRow.get("health")).isEqualTo("ACTION");

        assertThat(row(rows, "Beta GmbH").get("health"))
                .as("a failed voucher is a risk, not a task")
                .isEqualTo("RISK");
        assertThat(row(rows, "Beta GmbH").get("fehler")).isEqualTo(1);

        Map<String, Object> gammaRow = row(rows, "Gamma GmbH");
        assertThat(gammaRow.get("health")).isEqualTo("OK");
        assertThat(gammaRow.get("lastActivityAt")).isEqualTo("2026-03-05");
        assertThat(gammaRow.get("contactType")).isEqualTo("ORGANISATION");

        assertThat(row(rows, "Delta GmbH").get("health"))
                .as("vouchers accepted but untouched for more than fourteen days need a person")
                .isEqualTo("ACTION");

        Map<String, Object> epsilonRow = row(rows, "Epsilon GmbH");
        assertThat(epsilonRow.get("health")).as("a passed deadline is a risk").isEqualTo("RISK");
        assertThat(epsilonRow.get("deadlinesOpen"))
                .as("an overdue deadline is not open - it is late")
                .isEqualTo(0);

        Map<String, Object> zetaRow = row(rows, "Zeta GmbH");
        assertThat(zetaRow.get("health")).as("an upcoming deadline alone is not a problem").isEqualTo("OK");
        assertThat(zetaRow.get("deadlinesOpen")).isEqualTo(1);
    }

    @Test
    @DisplayName("the search finds a client by name, and the problem switch keeps only the ones that need work")
    void searchAndProblemFilter() throws Exception {
        Account account = register();
        UUID ok = mandant(account, "Ordentliche GmbH");
        document(account, ok, "EXPORTIERT", LocalDate.now().minusDays(1), null);
        UUID risk = mandant(account, "Risiko AG");
        document(account, risk, "FEHLER", LocalDate.now().minusDays(1), null);

        assertThat(list(account, "q=Risiko")).extracting(row -> row.get("displayName"))
                .containsExactly("Risiko AG");

        assertThat(list(account, "onlyProblems=true")).extracting(row -> row.get("displayName"))
                .containsExactly("Risiko AG");

        // The two filters compose: the search does not resurrect a healthy client.
        assertThat(list(account, "q=GmbH&onlyProblems=true")).isEmpty();
    }

    // ------------------------------------------------------------------ the card

    @Test
    @DisplayName("the card carries the newest ten vouchers and the deadlines that are still ahead")
    void cardShowsDocumentsAndDeadlines() throws Exception {
        Account account = register();
        UUID mandantId = mandant(account, "Kartenhaus GmbH");
        for (int day = 1; day <= 12; day++) {
            document(account, mandantId, "OFFEN", LocalDate.of(2026, 1, day), null);
        }
        UUID newest = document(account, mandantId, "ZU_PRUEFEN", LocalDate.of(2026, 2, 1), null);
        UUID openDocument = document(account, mandantId, "OFFEN", LocalDate.of(2026, 2, 2), null);
        deadline(account, openDocument, LocalDate.now().plusDays(5));
        deadline(account, openDocument, LocalDate.now().minusDays(5));

        MvcResult result = mockMvc.perform(get("/api/v1/mandanten/{id}", mandantId).cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> card = JsonPath.read(body(result), "$");

        assertThat(card.get("displayName")).isEqualTo("Kartenhaus GmbH");
        assertThat(card.get("documentsTotal")).isEqualTo(14);
        assertThat(card.get("health")).as("the passed deadline outweighs the upcoming one").isEqualTo("RISK");

        List<Map<String, Object>> documents = listField(card, "lastDocuments");
        assertThat(documents).as("the newest ten vouchers, not all fourteen").hasSize(10);
        assertThat(documents.getFirst().get("id")).isEqualTo(openDocument.toString());
        assertThat(documents.getFirst().get("belegStatus")).isEqualTo("OFFEN");
        assertThat(documents.getFirst().get("filename")).isEqualTo("beleg.pdf");
        assertThat(documents.get(1).get("id")).isEqualTo(newest.toString());
        assertThat(documents.get(1).get("date")).isEqualTo("2026-02-01");

        List<Map<String, Object>> deadlines = listField(card, "openDeadlines");
        assertThat(deadlines).as("only the deadline that has not passed").hasSize(1);
        assertThat(deadlines.getFirst().get("dueDate")).isEqualTo(LocalDate.now().plusDays(5).toString());
        assertThat(deadlines.getFirst().get("deadlineType")).isEqualTo("DOCUMENT_DATE");
        assertThat(card.get("deadlinesOpen")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the rule itself

    @Test
    @DisplayName("the fourteen-day boundary decides between OK and ACTION, and RISK always wins")
    void healthRuleBoundary() {
        Instant now = Instant.parse("2026-10-23T08:00:00Z");
        Instant thirteenDaysAgo = now.minus(Duration.ofDays(13));
        Instant fifteenDaysAgo = now.minus(Duration.ofDays(15));

        assertThat(MandantOverviewService.health(0, 0, 0, 0, null, now)).isEqualTo(Health.OK);
        assertThat(MandantOverviewService.health(0, 1, 0, 0, null, now))
                .as("a voucher to check is work waiting for a person")
                .isEqualTo(Health.ACTION);
        assertThat(MandantOverviewService.health(0, 0, 1, 0, thirteenDaysAgo, now))
                .as("thirteen days without movement is still business as usual")
                .isEqualTo(Health.OK);
        assertThat(MandantOverviewService.health(0, 0, 1, 0, fifteenDaysAgo, now))
                .as("fifteen days is the case the rule is about")
                .isEqualTo(Health.ACTION);
        assertThat(MandantOverviewService.health(0, 0, 1, 0, null, now))
                .as("an accepted voucher with no recorded movement is not silently OK")
                .isEqualTo(Health.ACTION);
        assertThat(MandantOverviewService.health(1, 1, 1, 0, fifteenDaysAgo, now))
                .as("a failed voucher outranks everything that is merely waiting")
                .isEqualTo(Health.RISK);
        assertThat(MandantOverviewService.health(0, 1, 0, 1, null, now))
                .as("a passed deadline outranks a voucher to check")
                .isEqualTo(Health.RISK);
    }

    // ------------------------------------------------------------------ isolation and emptiness

    @Test
    @DisplayName("a second account sees neither the client list nor the card of another cabinet")
    void anotherCabinetSeesNothing() throws Exception {
        Account owner = register();
        UUID mandantId = mandant(owner, "Fremde GmbH");
        document(owner, mandantId, "FEHLER", LocalDate.now().minusDays(1), null);

        Account stranger = register();
        assertThat(list(stranger, null)).as("an empty cabinet has no clients").isEmpty();

        mockMvc.perform(get("/api/v1/mandanten/{id}", mandantId).cookie(stranger.cookie()))
                .andExpect(status().isNotFound());

        // Row Level Security is the second guard: even a query written without the tenant filter sees nothing.
        Integer visible = RequestContext.runAs(stranger.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from contacts where id = ?
                """, Integer.class, mandantId));
        assertThat(visible).isZero();
    }

    @Test
    @DisplayName("a fresh cabinet gets an empty list, and a client without vouchers is OK rather than missing")
    void emptyCabinetAndEmptyClient() throws Exception {
        Account account = register();
        assertThat(list(account, null)).isEmpty();

        UUID withoutDocuments = mandant(account, "Neue Kundin");
        List<Map<String, Object>> rows = list(account, null);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = row(rows, "Neue Kundin");
        assertThat(row.get("documentsTotal")).isEqualTo(0);
        assertThat(row.get("health")).isEqualTo("OK");
        assertThat(row.get("lastActivityAt")).isNull();
        assertThat(row.get("deadlinesOpen")).isEqualTo(0);
    }
}

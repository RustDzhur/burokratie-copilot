package de.buerokratiecopilot.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The editable vocabulary of date words, from the database to the classification of a document (§9.3).
 *
 * <p><strong>The case this test protects.</strong> The words that explain a date used to be regular
 * expressions in {@code DateClassifier}. A word that was not written there did not exist, no user could add
 * it, and every new authority phrasing needed a deployment. Now the defaults come from migration V70 and a
 * workspace can add its own word or switch a default off; the same classifier reads both.</p>
 *
 * <p>These tests go through HTTP and a real PostgreSQL, because the parts that can be wrong are exactly the
 * ones a mock would hide: the row level security of the table, the "own word beats default" merge, the cache
 * that must drop after a write, and the classifier actually using the merged result.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DateMarkerSettingsIT {

    /**
     * How many words migration V70 ships. Asserted so a broken migration is noticed here rather than by a
     * user with a letter nobody can explain. If V70 legitimately changes, this number changes with it.
     */
    private static final int DEFAULT_WORDS = 255;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private record Account(Cookie cookie, UUID tenantId, UUID userId) {
    }

    // ------------------------------------------------------------------ setup

    private Account register() throws Exception {
        String email = "marker-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Wortschatz Test","accountType":"COMPANY"}
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

    /** A document with one page of text — the shape a photographed letter leaves behind. */
    private UUID letter(Account account, String pageText) {
        UUID documentId = jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, 'brief.pdf', 'brief.pdf', 'application/pdf', 10, ?,
                        'UPLOAD', 'READY', 'NOT_REQUIRED')
                returning id
                """, UUID.class, account.tenantId(),
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
        UUID blobId = jdbc.queryForObject("""
                insert into document_blobs (id, tenant_id, storage_key, size_bytes, content_hash,
                                            content_type, scan_status, quarantined)
                values (gen_random_uuid(), ?, ?, 10, ?, 'application/pdf', 'CLEAN', false)
                returning id
                """, UUID.class, account.tenantId(),
                "tenants/" + account.tenantId() + "/" + UUID.randomUUID() + ".pdf",
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
        UUID versionId = jdbc.queryForObject("""
                insert into document_versions (id, document_id, tenant_id, version, blob_id, content_hash,
                                               size_bytes, mime_type, original_filename, created_by)
                values (gen_random_uuid(), ?, ?, 1, ?, ?, 10, 'application/pdf', 'brief.pdf', ?)
                returning id
                """, UUID.class, documentId, account.tenantId(), blobId,
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()),
                account.userId());
        RequestContext.runAs(account.tenantId(), () -> jdbc.update("""
                insert into document_pages (id, document_id, version_id, page_number, text_content,
                                            ocr_used, text_blocks)
                values (gen_random_uuid(), ?, ?, 1, ?, true, '[]'::jsonb)
                """, documentId, versionId, pageText));
        return documentId;
    }

    // ------------------------------------------------------------------ calls

    private MvcResult call(Account account, MockHttpServletRequestBuilder request, String json) throws Exception {
        return mockMvc.perform(request.with(csrf()).cookie(account.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private List<Map<String, Object>> items(Account account) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/settings/date-markers").cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(body(result), "$.items");
    }

    private int count(Account account) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/settings/date-markers").cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(body(result), "$.count");
    }

    private Map<String, Object> item(Account account, String kind, String phrase) throws Exception {
        return items(account).stream()
                .filter(row -> kind.equals(row.get("kind")) && phrase.equals(row.get("phrase")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("word not served: " + kind + "/" + phrase));
    }

    /** The kind the document's single date is classified as, through the production path. */
    private String classifiedKind(Account account, UUID documentId) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/documents/{id}/detected-dates", documentId).cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        List<String> kinds = JsonPath.read(body(result), "$.dates[*].kind");
        assertThat(kinds).as("the document must contain exactly one date").hasSize(1);
        return kinds.getFirst();
    }

    // ------------------------------------------------------------------ the defaults

    @Test
    @DisplayName("the defaults from V70 are served as readable words, and they classify a document")
    void defaultsAreServed() throws Exception {
        Account account = register();

        assertThat(count(account)).as("V70 ships the default vocabulary").isEqualTo(DEFAULT_WORDS);
        Map<String, Object> zahlung = item(account, "PAYMENT_DEADLINE", "zahlung");
        assertThat(zahlung.get("system")).isEqualTo(true);
        assertThat(zahlung.get("tenantOverride")).isEqualTo(false);
        assertThat(zahlung.get("enabled")).isEqualTo(true);
        assertThat(zahlung.get("id")).isNotNull();
        assertThat(zahlung).doesNotContainKey("tenant_id");
        // Every served word names a kind that belongs to the taxonomy; OTHER is not an editable word.
        assertThat(items(account)).extracting(row -> row.get("kind"))
                .doesNotContain("OTHER");

        UUID documentId = letter(account,
                "Sehr geehrte Damen und Herren, wir bitten um Zahlung bis zum 31.10.2026.");
        assertThat(classifiedKind(account, documentId))
                .as("the production path must read the words from the database")
                .isEqualTo("PAYMENT_DEADLINE");
    }

    // ------------------------------------------------------------------ own words

    @Test
    @DisplayName("a workspace's own word works for the next document, without a deploy")
    void ownWordTakesEffect() throws Exception {
        Account account = register();
        UUID documentId = letter(account, "Bitte beachten Sie den Hausbesuch am 15.03.2027.");
        assertThat(classifiedKind(account, documentId))
                .as("without the word the date has no explanation")
                .isEqualTo("OTHER");

        MvcResult created = call(account, post("/api/v1/settings/date-markers"),
                """
                        {"kind":"APPOINTMENT","phrase":"  Hausbesuch ","label":"Hausbesuch der Behörde"}
                        """);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) JsonPath.read(body(created), "$.phrase")).isEqualTo("hausbesuch");
        assertThat((String) JsonPath.read(body(created), "$.label")).isEqualTo("Hausbesuch der Behörde");
        assertThat((Boolean) JsonPath.read(body(created), "$.enabled")).isEqualTo(true);
        assertThat((Boolean) JsonPath.read(body(created), "$.system")).isEqualTo(false);
        assertThat((Boolean) JsonPath.read(body(created), "$.tenantOverride")).isEqualTo(false);

        // The classifier caches the vocabulary; a write has to drop the cache or the new word would need a
        // restart (or a minute) to be noticed.
        assertThat(classifiedKind(account, documentId)).isEqualTo("APPOINTMENT");
        assertThat(count(account)).isEqualTo(DEFAULT_WORDS + 1);
        assertThat(RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from audit_events where tenant_id = ? and action = 'DATE_MARKER_CREATED'",
                Integer.class, account.tenantId())))
                .as("a changed vocabulary is an audited change")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("switching a default off by a word with the same kind and phrase, and deleting it restores the default")
    void disablingADefaultWorks() throws Exception {
        Account account = register();
        UUID documentId = letter(account, "Vorgang vom 12.11.2026");
        assertThat(classifiedKind(account, documentId)).isEqualTo("DOCUMENT_DATE");
        String defaultId = (String) item(account, "DOCUMENT_DATE", "vom").get("id");

        MvcResult disabled = call(account, post("/api/v1/settings/date-markers"),
                """
                        {"kind":"DOCUMENT_DATE","phrase":"vom","enabled":false}
                        """);
        assertThat(disabled.getResponse().getStatus()).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(body(disabled), "$.enabled")).isEqualTo(false);
        assertThat((Boolean) JsonPath.read(body(disabled), "$.tenantOverride")).isEqualTo(true);
        assertThat((String) JsonPath.read(body(disabled), "$.id"))
                .as("the workspace row, not the platform row")
                .isNotEqualTo(defaultId);

        Map<String, Object> effective = item(account, "DOCUMENT_DATE", "vom");
        assertThat(effective.get("enabled")).isEqualTo(false);
        // system says "the phrase comes from the standard vocabulary" - and it does, even though this row is
        // the workspace's own. tenantOverride says who owns the row, which is what makes it editable.
        assertThat(effective.get("system")).isEqualTo(true);
        assertThat(effective.get("tenantOverride")).isEqualTo(true);
        assertThat(count(account)).as("switching off replaces the default, it does not add a word")
                .isEqualTo(DEFAULT_WORDS);
        assertThat(classifiedKind(account, documentId)).isEqualTo("OTHER");

        String id = (String) effective.get("id");
        assertThat(mockMvc.perform(delete("/api/v1/settings/date-markers/{id}", id)
                .with(csrf()).cookie(account.cookie())).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(classifiedKind(account, documentId))
                .as("deleting the workspace word lets the default apply again")
                .isEqualTo("DOCUMENT_DATE");
        assertThat(item(account, "DOCUMENT_DATE", "vom").get("tenantOverride")).isEqualTo(false);
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    @DisplayName("a platform default cannot be changed or deleted; the answer points to the workspace word")
    void defaultsAreNotEditable() throws Exception {
        Account account = register();
        Map<String, Object> zahlung = item(account, "PAYMENT_DEADLINE", "zahlung");
        String id = (String) zahlung.get("id");

        MvcResult patched = call(account, patch("/api/v1/settings/date-markers/{id}", id),
                "{\"enabled\":false}");
        assertThat(patched.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(body(patched), "$.code")).isEqualTo("CONFLICT");

        MvcResult deleted = mockMvc.perform(delete("/api/v1/settings/date-markers/{id}", id)
                .with(csrf()).cookie(account.cookie())).andReturn();
        assertThat(deleted.getResponse().getStatus()).isEqualTo(409);
        assertThat(item(account, "PAYMENT_DEADLINE", "zahlung").get("enabled")).isEqualTo(true);
    }

    @Test
    @DisplayName("another workspace neither sees nor changes the words, and its own classifier does not use them")
    void anotherWorkspaceIsIsolated() throws Exception {
        Account first = register();
        Account second = register();
        MvcResult created = call(first, post("/api/v1/settings/date-markers"),
                "{\"kind\":\"APPOINTMENT\",\"phrase\":\"hausbesuch\"}");
        String foreignId = JsonPath.read(body(created), "$.id");
        UUID document = letter(first, "Bitte beachten Sie den Hausbesuch am 15.03.2027.");
        assertThat(classifiedKind(first, document)).isEqualTo("APPOINTMENT");

        assertThat(items(second)).extracting(row -> row.get("phrase")).doesNotContain("hausbesuch");
        assertThat(count(second)).isEqualTo(DEFAULT_WORDS);

        // An identifier from another workspace is not found, not forbidden: the answer must not confirm that
        // somebody else's word exists.
        assertThat(call(second, patch("/api/v1/settings/date-markers/{id}", foreignId),
                "{\"enabled\":false}").getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(delete("/api/v1/settings/date-markers/{id}", foreignId)
                .with(csrf()).cookie(second.cookie())).andReturn().getResponse().getStatus()).isEqualTo(404);

        UUID secondDocument = letter(second, "Bitte beachten Sie den Hausbesuch am 15.03.2027.");
        assertThat(classifiedKind(second, secondDocument))
                .as("the first workspace's word must not classify the second's letter")
                .isEqualTo("OTHER");
    }

    @Test
    @DisplayName("a word is sanitised and validated: kind, length, duplicate")
    void validation() throws Exception {
        Account account = register();

        assertThat(call(account, post("/api/v1/settings/date-markers"),
                "{\"kind\":\"FREEDOM\",\"phrase\":\"hausbesuch\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(call(account, post("/api/v1/settings/date-markers"),
                "{\"kind\":\"OTHER\",\"phrase\":\"hausbesuch\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(call(account, post("/api/v1/settings/date-markers"),
                "{\"phrase\":\"hausbesuch\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(call(account, post("/api/v1/settings/date-markers"),
                "{\"kind\":\"APPOINTMENT\",\"phrase\":\"a\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(call(account, post("/api/v1/settings/date-markers"),
                "{\"kind\":\"APPOINTMENT\",\"phrase\":\"%s\"}".formatted("x".repeat(121)))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(call(account, post("/api/v1/settings/date-markers"),
                "{\"kind\":\"APPOINTMENT\",\"phrase\":\"hausbesuch\",\"label\":\"%s\"}"
                        .formatted("x".repeat(201))).getResponse().getStatus()).isEqualTo(400);

        MvcResult created = call(account, post("/api/v1/settings/date-markers"),
                "{\"kind\":\"APPOINTMENT\",\"phrase\":\"  Haus   Besuch  \"}");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) JsonPath.read(body(created), "$.phrase")).isEqualTo("haus besuch");

        assertThat(call(account, post("/api/v1/settings/date-markers"),
                "{\"kind\":\"APPOINTMENT\",\"phrase\":\"haus  besuch\"}").getResponse().getStatus())
                .as("the same word twice is refused instead of silently duplicated")
                .isEqualTo(409);

        String id = JsonPath.read(body(created), "$.id");
        MvcResult patched = call(account, patch("/api/v1/settings/date-markers/{id}", id),
                "{\"enabled\":false,\"phrase\":\"Neuer   Begriff\"}");
        assertThat(patched.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body(patched), "$.phrase")).isEqualTo("neuer begriff");
        assertThat((Boolean) JsonPath.read(body(patched), "$.enabled")).isEqualTo(false);

        assertThat(call(account, patch("/api/v1/settings/date-markers/{id}", UUID.randomUUID()),
                "{\"enabled\":true}").getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ reset

    @Test
    @DisplayName("reset removes every own word and the defaults apply again")
    void reset() throws Exception {
        Account account = register();
        call(account, post("/api/v1/settings/date-markers"),
                "{\"kind\":\"APPOINTMENT\",\"phrase\":\"hausbesuch\"}");
        call(account, post("/api/v1/settings/date-markers"),
                "{\"kind\":\"DOCUMENT_DATE\",\"phrase\":\"vom\",\"enabled\":false}");
        assertThat(count(account)).isEqualTo(DEFAULT_WORDS + 1);

        MvcResult reset = call(account, post("/api/v1/settings/date-markers:reset"), "");
        assertThat(reset.getResponse().getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(reset), "$.deleted")).isEqualTo(2);

        assertThat(count(account)).isEqualTo(DEFAULT_WORDS);
        assertThat(items(account)).extracting(row -> row.get("phrase")).doesNotContain("hausbesuch");
        Map<String, Object> vom = item(account, "DOCUMENT_DATE", "vom");
        assertThat(vom.get("system")).isEqualTo(true);
        assertThat(vom.get("tenantOverride")).isEqualTo(false);
        assertThat(vom.get("enabled")).isEqualTo(true);

        UUID document = letter(account, "Vorgang vom 12.11.2026");
        assertThat(classifiedKind(account, document)).isEqualTo("DOCUMENT_DATE");
    }

    // ------------------------------------------------------------------ access

    @Test
    @DisplayName("without a session the vocabulary is not served and not changed")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/settings/date-markers")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/settings/date-markers").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"APPOINTMENT\",\"phrase\":\"hausbesuch\"}"))
                .andExpect(status().isUnauthorized());
    }
}

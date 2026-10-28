package de.buerokratiecopilot.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

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

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/** S10-07b / E1: two documents of one kind with one reference that disagree on a date or an amount. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConflictingFactsRuleIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RadarEngine engine;

    private UUID tenantId;
    private Cookie cookie;

    private void tenant() throws Exception {
        String email = "e1-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"E\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        tenantId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andReturn().getResponse().getCookie("bc_session");
    }

    private UUID document(String type, String text) throws Exception {
        MvcResult r = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "Dok.pdf", "application/pdf", TestDocuments.pdf(text + " " + UUID.randomUUID()))).with(csrf()).cookie(cookie))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.document.id"));
        RequestContext.runAs(tenantId, () -> jdbc.update(
                "update documents set document_type_id = (select id from document_types where code = ?) where id = ?", type, id));
        return id;
    }

    private void fact(UUID document, String key, String value, String valueType) {
        RequestContext.runAs(tenantId, () -> jdbc.update("""
                insert into document_extractions (document_id, field_key, field_value, normalized_value, value_type, confidence, source_snippet, extractor, source_kind)
                values (?, ?, ?, ?, ?, 0.9, ?, 'RULE', 'TEXT')
                """, document, key, value, value, valueType, key + " " + value));
    }

    private List<Map<String, Object>> findings() {
        return RequestContext.runAs(tenantId, () -> jdbc.queryForList(
                "select * from radar_findings where tenant_id = ? and rule_id = 'E1' order by created_at", tenantId));
    }

    private void evaluate(UUID document) {
        RequestContext.runAs(tenantId, () -> engine.evaluateDocument(tenantId, document));
    }

    @Test
    @DisplayName("E1: the same file number with another deadline is HIGH, with another amount MEDIUM; both documents are the evidence")
    void conflicts() throws Exception {
        tenant();
        UUID a = document("preiserhoehung", "Preiserhöhung eins");
        UUID b = document("preiserhoehung", "Preiserhöhung zwei");
        for (UUID d : List.of(a, b)) {
            fact(d, "Aktenzeichen", "AZ-2026-77", "REFERENCE");
        }
        fact(a, "Frist", "2026-11-30", "DATE");
        fact(b, "Frist", "2026-12-15", "DATE");
        fact(a, "Betrag", "100.00", "AMOUNT");
        fact(b, "Betrag", "100.0", "AMOUNT");
        evaluate(b);

        List<Map<String, Object>> found = findings();
        assertThat(found).hasSize(1);
        assertThat(found.getFirst()).containsEntry("severity", "HIGH").containsEntry("finding_type", "CONFLICTING_FACTS");
        assertThat(RequestContext.runAs(tenantId, () -> jdbc.queryForObject(
                "select count(*) from radar_finding_evidence where finding_id = ?", Integer.class, found.getFirst().get("id")))).isEqualTo(2);

        fact(b, "Betrag", "120.00", "AMOUNT");
        jdbc.update("delete from document_extractions where document_id = ? and field_key = 'Betrag' and normalized_value = '100.0'", b);
        evaluate(b);
        assertThat(findings()).extracting(f -> f.get("severity")).containsExactlyInAnyOrder("HIGH", "MEDIUM");
    }

    @Test
    @DisplayName("E1 stays silent: equal values, another reference, another kind of document, or no reference at all")
    void silent() throws Exception {
        tenant();
        UUID a = document("preiserhoehung", "Preiserhöhung eins");
        UUID b = document("preiserhoehung", "Preiserhöhung zwei");
        UUID c = document("zahlungserinnerung", "Erinnerung");
        UUID d = document("preiserhoehung", "Preiserhöhung drei");
        for (UUID x : List.of(a, b, c)) {
            fact(x, "Aktenzeichen", "AZ-1", "REFERENCE");
        }
        fact(d, "Aktenzeichen", "AZ-OTHER", "REFERENCE");
        fact(a, "Frist", "2026-11-30", "DATE");
        fact(b, "Frist", "2026-11-30", "DATE");
        fact(c, "Frist", "2027-01-31", "DATE");
        fact(d, "Frist", "2027-02-02", "DATE");
        UUID noReference = document("preiserhoehung", "ohne Aktenzeichen");
        fact(noReference, "Frist", "2030-01-01", "DATE");
        for (UUID x : List.of(a, b, c, d, noReference)) {
            evaluate(x);
        }
        assertThat(findings()).isEmpty();
    }
}

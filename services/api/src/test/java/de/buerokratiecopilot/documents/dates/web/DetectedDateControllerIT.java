package de.buerokratiecopilot.documents.dates.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * A date found in a photographed letter, offered for the calendar (spec §13.2).
 *
 * <p><strong>This test is the case that failed in real use.</strong> A person photographed a letter with a
 * deadline on it and no calendar entry appeared, although the calendar had been promised to fill itself. The
 * cause was not OCR: deadlines are computed from a register of legal rules, that register ships empty by
 * design, and "the date written on the paper" is not something a rule computes. So the date was read and
 * discarded.</p>
 *
 * <p>The test therefore uses <strong>no configured fields and no rules at all</strong> — only a page of text
 * with a date in it — and asserts that the date is found, that the words around it say what it is, and that
 * the sentence comes with it. The entry itself is made automatically when the document finishes processing,
 * so there is no button and no second call to test here.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DetectedDateControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "dat-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Termin Test","accountType":"COMPANY"}
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
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    /** A document with one stored version and a page of text — the shape a photograph leaves behind. */
    private UUID photographedLetter(Session session, String pageText) {
        UUID documentId = jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, 'brief.pdf', 'brief.pdf', 'application/pdf', 10, ?,
                        'UPLOAD', 'READY', 'NOT_REQUIRED')
                returning id
                """, UUID.class, session.tenantId(),
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
        UUID blobId = jdbc.queryForObject("""
                insert into document_blobs (id, tenant_id, storage_key, size_bytes, content_hash,
                                            content_type, scan_status, quarantined)
                values (gen_random_uuid(), ?, ?, 10, ?, 'application/pdf', 'CLEAN', false)
                returning id
                """, UUID.class, session.tenantId(),
                "tenants/" + session.tenantId() + "/" + UUID.randomUUID() + ".pdf",
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
        UUID versionId = jdbc.queryForObject("""
                insert into document_versions (id, document_id, tenant_id, version, blob_id, content_hash,
                                               size_bytes, mime_type, original_filename, created_by)
                values (gen_random_uuid(), ?, ?, 1, ?, ?, 10, 'application/pdf', 'brief.pdf', ?)
                returning id
                """, UUID.class, documentId, session.tenantId(), blobId,
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()),
                session.userId());
        final UUID version = versionId;
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into document_pages (id, document_id, version_id, page_number, text_content,
                                            ocr_used, text_blocks)
                values (gen_random_uuid(), ?, ?, 1, ?, true, '[]'::jsonb)
                """, documentId, version, pageText));
        return documentId;
    }

    // ---------------------------------------------------------------- the case that failed

    @Test
    @DisplayName("a date printed in a photographed letter is found, and shown with its sentence")
    void aDateInAPhotographedLetterIsFound() throws Exception {
        Session session = register();
        UUID documentId = photographedLetter(session,
                "Sehr geehrte Damen und Herren, wir bitten um Zahlung bis zum 31.10.2026. "
                        + "Mit freundlichen Grüßen");

        // No configured fields, no legal rules, no document type: exactly the case that produced nothing
        // before, and the reason is that a printed date is data, not a computed legal deadline.
        MvcResult result = mockMvc.perform(
                        get("/api/v1/documents/{id}/detected-dates", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.dates[0].date").value("2026-10-31"))
                .andExpect(jsonPath("$.dates[0].page").value(1))
                .andExpect(jsonPath("$.dates[0].origin").value("PAGE_TEXT"))
                // The wording says what the date is: a payment date, not a letter date, which is why it
                // becomes a calendar entry on its own.
                .andExpect(jsonPath("$.dates[0].kind").value("PAYMENT_DEADLINE"))
                .andReturn();

        // The sentence travels with the date: a date without its source cannot be checked, and a wrong date
        // in a calendar is worse than an empty one.
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("Zahlung bis zum");
    }

    @Test
    @DisplayName("a document with no readable date says so without claiming there is no deadline")
    void noDateIsNotTheSameAsNoDeadline() throws Exception {
        Session session = register();
        UUID documentId = photographedLetter(session, "Ein Schreiben ohne Datum.");

        // The distinction that caused the confusion: a document with nothing read is not a document with no
        // deadline, and the answer has to say which it is.
        MvcResult result = mockMvc.perform(
                        get("/api/v1/documents/{id}/detected-dates", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(
                        JsonPath.read(result.getResponse().getContentAsString(), "$.note").toString())
                .contains("keine Frist");
    }

    @Test
    @DisplayName("the same date written twice is offered once")
    void theSameDateIsOfferedOnce() throws Exception {
        Session session = register();
        UUID documentId = photographedLetter(session,
                "Frist: 31.10.2026. Gezahlt werden muss bis 31.10.2026.");

        // Two calendar entries for one deadline is how a calendar becomes something people stop trusting.
        mockMvc.perform(get("/api/v1/documents/{id}/detected-dates", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read and nothing is entered")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{id}/detected-dates", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another account sees no dates of this document")
    void anotherAccountSeesNothing() throws Exception {
        Session first = register();
        Session second = register();
        UUID documentId = photographedLetter(first, "Zahlung bis zum 31.10.2026.");

        MvcResult foreign = mockMvc.perform(
                        get("/api/v1/documents/{id}/detected-dates", documentId)
                                .cookie(second.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(foreign.getResponse().getContentAsString())
                .as("nothing of the first account's letter may appear in the second's answer")
                .doesNotContain("31.10.2026");
    }
}

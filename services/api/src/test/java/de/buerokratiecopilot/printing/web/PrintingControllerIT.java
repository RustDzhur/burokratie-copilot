package de.buerokratiecopilot.printing.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * Printing over HTTP (spec §27).
 *
 * <p>What this checks beyond the routes: the PDF is an <strong>attachment</strong> rather than something a
 * browser renders in a tab somebody walked away from; the file name is built from a title that is user input
 * and is <strong>sanitised</strong>, because a newline left alone in a {@code Content-Disposition} header is
 * how a title becomes a header of the sender's choosing; and a short label run
 * <strong>says so in the answer</strong>.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PrintingControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "prn-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Druck Test","accountType":"COMPANY"}
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

    /** A case to print against: the service refuses to print without a subject, and that is the rule. */
    private UUID caseFor(Session session) {
        return jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title, status)
                values (gen_random_uuid(), ?, ?, 'Ein Verfahren', 'NEW') returning id
                """, UUID.class, session.tenantId(),
                "AZ-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private String letter(String title, UUID subjectId) {
        return """
                {"title":"%s","subjectType":"CASE","subjectId":"%s",
                 "lines":["Sehr geehrte Damen und Herren,","mit freundlichen Grüßen"],
                 "footer":"Muster GmbH"}
                """.formatted(title, subjectId);
    }

    // ---------------------------------------------------------------- the letter

    @Test
    @DisplayName("a letter is rendered as a PDF attachment")
    void aLetterIsRenderedAsAnAttachment() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/printing/documents").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(letter("Widerspruch", caseFor(session))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                // An attachment rather than inline: a PDF rendered in a browser tab is a document somebody
                // can screenshot out of a session they have walked away from.
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("Widerspruch.pdf")));
    }

    @Test
    @DisplayName("a letter without a title or without lines is refused")
    void aLetterNeedsItsFields() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/printing/documents").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[\"Text\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/printing/documents").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ohne Zeilen\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- the file name

    @Test
    @DisplayName("a title cannot become a header of the sender's choosing")
    void aTitleCannotBecomeAHeader() throws Exception {
        Session session = register();
        // A newline in a Content-Disposition header value, left alone, ends the header and starts another.
        String hostile = "Widerspruch\\r\\nX-Injected: ja";

        MvcResult result = mockMvc.perform(post("/api/v1/printing/documents").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(letter(hostile, caseFor(session))))
                .andExpect(status().isOk())
                .andReturn();

        // What must not survive is the carriage return and the newline: those are what ends the header and
        // starts another. The words are harmless - 'X-Injected' as part of a file name is a file name. The
        // first version of this assertion checked the words, which said nothing about the danger.
        String disposition = result.getResponse().getHeader("Content-Disposition");
        org.assertj.core.api.Assertions.assertThat(disposition)
                .as("the disposition must be one header, with the title reduced to a file name")
                .doesNotContain("\r")
                .doesNotContain("\n")
                .doesNotContain(": ja");
    }

    @Test
    @DisplayName("the file name is reduced to letters, digits and a few safe characters")
    void theFileNameIsReduced() {
        // The rule the endpoint relies on, checked directly: everything that could change the meaning of the
        // header is replaced, and a title that reduces to nothing gets a fixed name rather than an empty one.
        org.assertj.core.api.Assertions.assertThat(PrintingController.safeFileName("Widerspruch 2026/01"))
                .isEqualTo("Widerspruch_2026_01");
        // The case that matters: a carriage return and a newline must not survive into the header.
        org.assertj.core.api.Assertions.assertThat(
                        PrintingController.safeFileName("Widerspruch\r\nX-Injected: ja"))
                .as("a newline in a title must not reach the header")
                .doesNotContain("\r")
                .doesNotContain("\n")
                .doesNotContain(":");
        org.assertj.core.api.Assertions.assertThat(PrintingController.safeFileName("../etc/passwd"))
                .isEqualTo(".._etc_passwd");
        org.assertj.core.api.Assertions.assertThat(PrintingController.safeFileName("///"))
                .isEqualTo("druck");
        org.assertj.core.api.Assertions.assertThat(PrintingController.safeFileName(null))
                .isEqualTo("druck");
        org.assertj.core.api.Assertions.assertThat(PrintingController.safeFileName("a".repeat(200)))
                .hasSize(80);
    }

    // ---------------------------------------------------------------- registers and labels

    @Test
    @DisplayName("a register is rendered, and one without headings is refused")
    void aRegisterNeedsHeadings() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/printing/registers").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Fristen","subjectType":"CASE","subjectId":"%s",
                                 "columnHeaders":["Akte","Frist"],
                                 "rows":[["2026-1","2026-12-31"]]}
                                """.formatted(caseFor(session))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        mockMvc.perform(post("/api/v1/printing/registers").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ohne Spalten\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a label run reports how many labels came back")
    void aLabelRunReportsItsCount() throws Exception {
        Session session = register();

        // Printing ten labels and receiving eight is the failure that matters, and it is invisible if the
        // answer is only a list.
        mockMvc.perform(post("/api/v1/printing/labels").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[%s,%s]}"
                                .formatted(letter("Etikett eins", caseFor(session)),
                                        letter("Etikett zwei", caseFor(session)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.produced").value(2))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.references").isArray())
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("a label run without labels is refused, and a label without a title is refused")
    void aLabelRunNeedsLabels() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/printing/labels").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/printing/labels").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[{\"lines\":[\"Text\"]}]}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is printed")
    void withoutASessionNothingIsPrinted() throws Exception {
        mockMvc.perform(post("/api/v1/printing/documents").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(letter("Ohne Sitzung", UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/printing/labels").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the export permission exists and is held, which is what makes these reachable")
    void theExportPermissionIsHeld() {
        // A PDF is a document leaving the platform, which is what documents.export is for; naming it that
        // way lets an account decide who may take documents out rather than who may press a button.
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code = 'documents.export'
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("documents.export must be granted to OWNER and ADMIN, or these are dead")
                .isGreaterThan(0);
    }
}

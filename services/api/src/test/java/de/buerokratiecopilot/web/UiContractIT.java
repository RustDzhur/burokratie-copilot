package de.buerokratiecopilot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/**
 * The calls the web client makes, answered by the real application and the real database.
 *
 * <p>Why this exists: the client and the server were each tested against a stand-in for the other, so both
 * were green while the client called {@code 43} routes the server did not have (docs/completion-audit.md,
 * C1) and could not even sign in (C2). {@code contract.test.ts} on the client side proves every route
 * exists; this class proves the routes answer, with the fields the client's adapters read.</p>
 *
 * <p>The tests share one account, created once, and are ordered because later ones use what earlier ones
 * made (a task needs a case to show its reference, a template list needs a template).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Its own application context, and never csrf(): that post-processor swaps the CSRF repository of the filter it
// meets for a test double and leaves it there, which would make the real double-submit check below pass or
// fail depending on which test class ran before. Here every request carries the token the way a browser does.
@org.springframework.test.context.TestPropertySource(properties = "bc.test.context=ui-contract")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UiContractIT {

    private static final String PASSWORD = "EinSicheresPasswort-2026!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    private static Cookie session;
    private static String userId;
    private static String email;
    private static String caseId;
    private static Cookie xsrfCookie;

    // ------------------------------------------------------------------ sign-in as the browser does it

    @Test
    @Order(1)
    @DisplayName("sign-in works with the real double-submit token: GET /auth/csrf, then the header on the POST")
    void browserSignInWithCsrfToken() throws Exception {
        email = "ui-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";

        // Registration is a POST too, so it needs the token as well.
        Cookie xsrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        xsrfCookie = xsrf;

        MvcResult registration = mvc.perform(post("/api/v1/auth/register")
                        .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","displayName":"Erika Mustermann",
                                 "accountType":"COMPANY"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
        userId = JsonPath.read(registration.getResponse().getContentAsString(), "$.userId");
        String tenantId = JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId");

        RequestContext.runAs(UUID.fromString(tenantId), () -> {
            User user = users.findById(UUID.fromString(userId)).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });

        // Without the header the same request is refused: this is what the client used to run into.
        mvc.perform(post("/api/v1/auth/login").cookie(xsrf).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isForbidden());

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        session = login.getResponse().getCookie("bc_session");
        assertThat(session).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("/auth/me describes the person for the client, and onboarding flips its flag")
    void meAndOnboarding() throws Exception {
        mvc.perform(authed(get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.workspaceName").exists())
                .andExpect(jsonPath("$.roles[0]").value("OWNER"))
                .andExpect(jsonPath("$.onboardingCompleted").value(false));

        mvc.perform(authed(post("/api/v1/auth/onboarding")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceName":"Muster Hausverwaltung","workspaceType":"company",
                                 "sources":["upload","email"],"notifications":["deadlines","review","weekly"]}
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(authed(get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                .andExpect(jsonPath("$.workspaceName").value("Muster Hausverwaltung"))
                .andExpect(jsonPath("$.accountType").value("COMPANY"));

        mvc.perform(authed(post("/api/v1/auth/onboarding")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceName\":\"x\",\"workspaceType\":\"banana\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ documents

    @Test
    @Order(10)
    @DisplayName("documents: upload, filtered list, card with fields, reprocess, review queue")
    void documents() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "Rechnung.pdf", "application/pdf",
                TestDocuments.pdf("Rechnung ui-contract"));
        MvcResult uploaded = mvc.perform(authed(multipart("/api/v1/documents").file(file)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.id").exists())
                .andExpect(jsonPath("$.document.originalFilename").value("Rechnung.pdf"))
                .andReturn();
        String documentId = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.document.id");

        // The screen's filters arrive as repeated enum names; the Spring page carries totalElements.
        mvc.perform(authed(get("/api/v1/documents")
                        .param("status", "RECEIVED", "OCR_PENDING", "EXTRACTING", "READY", "REVIEW_REQUIRED",
                                "COMPLETED", "FAILED", "QUARANTINED")
                        .param("source", "UPLOAD").param("sort", "originalFilename,asc").param("size", "5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].processingStatus").exists())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber());

        mvc.perform(authed(get("/api/v1/documents/" + documentId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("UPLOAD"));
        mvc.perform(authed(get("/api/v1/documents/" + documentId + "/fields")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fields").isArray());
        mvc.perform(authed(get("/api/v1/audit/events").param("entityType", "documents")
                        .param("entityId", documentId).param("limit", "50")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events").isArray());

        mvc.perform(authed(post("/api/v1/documents/" + documentId + "/reprocess")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(documentId));
        mvc.perform(authed(get("/api/v1/reviews"))).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(authed(get("/api/v1/documents/" + documentId + "/content"))).andExpect(status().isOk());

        // Nothing can be decided about a document that has no open review.
        mvc.perform(authed(get("/api/v1/reviews/" + documentId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.openReviews").isArray());
    }

    // ------------------------------------------------------------------ work: cases, tasks, calendar

    @Test
    @Order(20)
    @DisplayName("cases and tasks: create, filter by status and priority, names and references in the rows")
    void casesAndTasks() throws Exception {
        MvcResult created = mvc.perform(authed(post("/api/v1/cases")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Steuerbescheid 2025\"}"))
                .andExpect(status().isCreated()).andReturn();
        caseId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(authed(get("/api/v1/cases").param("status", "NEW", "IN_PROGRESS").param("priority", "NORMAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reference").exists())
                .andExpect(jsonPath("$.content[0].updatedAt").exists())
                .andExpect(jsonPath("$.content[0].responsibleName").exists());
        mvc.perform(authed(get("/api/v1/cases").param("status", "CLOSED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(authed(get("/api/v1/cases/" + caseId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.caseRecord.id").value(caseId))
                .andExpect(jsonPath("$.requiredDocuments").isArray());

        String due = LocalDate.now().plusDays(3) + "T12:00:00Z";
        MvcResult task = mvc.perform(authed(post("/api/v1/tasks")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Bescheid prüfen","priority":"HIGH","dueAt":"%s",
                                 "caseId":"%s","assigneeUserId":"%s"}
                                """.formatted(due, caseId, userId)))
                .andExpect(status().isCreated()).andReturn();
        String taskId = JsonPath.read(task.getResponse().getContentAsString(), "$.id");

        mvc.perform(authed(get("/api/v1/tasks").param("status", "NEW", "IN_PROGRESS").param("priority", "HIGH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Bescheid prüfen"))
                .andExpect(jsonPath("$.content[0].assigneeNames[0]").value("Erika Mustermann"))
                .andExpect(jsonPath("$.content[0].caseReference").exists());
        mvc.perform(authed(get("/api/v1/tasks").param("priority", "LOW")))
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(authed(post("/api/v1/tasks/" + taskId + "/status")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\",\"reason\":\"wartet auf Unterlagen\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BLOCKED"));
        // A task without a stated completion criterion cannot be finished: that rule is the backend's, and the
        // client's create form therefore asks for one (see tasks/page.tsx).
        mvc.perform(authed(post("/api/v1/tasks/" + taskId + "/complete")).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        MvcResult withCriterion = mvc.perform(authed(post("/api/v1/tasks")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Beleg ablegen\",\"completionCriteria\":\"Beleg ist im Ordner\","
                                + "\"assigneeUserId\":\"%s\"}".formatted(userId)))
                .andExpect(status().isCreated()).andReturn();
        String finishable = JsonPath.read(withCriterion.getResponse().getContentAsString(), "$.id");
        mvc.perform(authed(post("/api/v1/tasks/" + finishable + "/complete")).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    @Order(21)
    @DisplayName("calendar: an appointment is stored; deadlines of the person's work appear next to it")
    void calendar() throws Exception {
        String day = LocalDate.now().plusDays(5).toString();
        mvc.perform(authed(post("/api/v1/calendar/events")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Termin Finanzamt\",\"date\":\"%s\",\"type\":\"APPOINTMENT\"}"
                                .formatted(day)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("APPOINTMENT"));
        mvc.perform(authed(get("/api/v1/calendar/events").param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().plusDays(30).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Termin Finanzamt')].date").isNotEmpty());

        mvc.perform(authed(post("/api/v1/calendar/events")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"date\":\"%s\",\"type\":\"REMINDER\"}".formatted(day)))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(get("/api/v1/calendar/events").param("from", "2020-01-01").param("to", "2030-01-01")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ money

    @Test
    @Order(30)
    @DisplayName("invoices, bank transactions and contracts answer in the shape the adapters read")
    void finance() throws Exception {
        MvcResult invoice = mvc.perform(authed(post("/api/v1/invoices")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invoiceNumber":"R-2026-1","supplierName":"Stadtwerke Muster","direction":"INCOMING",
                                 "currency":"EUR","grossAmount":"119.00","dueDate":"%s"}
                                """.formatted(LocalDate.now().plusDays(14))))
                .andExpect(status().isCreated()).andReturn();
        assertThat(JsonPath.<String>read(invoice.getResponse().getContentAsString(), "$.invoiceNumber"))
                .isEqualTo("R-2026-1");

        mvc.perform(authed(get("/api/v1/invoices").param("status", "RECEIVED", "IN_REVIEW").param("direction", "INCOMING")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].invoiceNumber").value("R-2026-1"))
                .andExpect(jsonPath("$.content[0].supplierName").value("Stadtwerke Muster"))
                .andExpect(jsonPath("$.totalElements").isNumber());

        mvc.perform(authed(get("/api/v1/banking/transactions").param("status", "UNMATCHED", "SUGGESTED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
        mvc.perform(authed(get("/api/v1/banking/transactions").param("status", "BOGUS")))
                .andExpect(status().isBadRequest());

        MvcResult contract = mvc.perform(authed(post("/api/v1/contracts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Mietvertrag\",\"contractType\":\"RENT\",\"endDate\":\"%s\"}"
                                .formatted(LocalDate.now().plusDays(60))))
                .andExpect(status().isCreated()).andReturn();
        String contractId = JsonPath.read(contract.getResponse().getContentAsString(), "$.id");
        mvc.perform(authed(post("/api/v1/contracts/" + contractId + "/parties")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partyRole\":\"COUNTERPARTY\",\"partyName\":\"Vermieter GmbH\",\"primary\":true}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(authed(get("/api/v1/contracts")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].partnerName").value("Vermieter GmbH"));
        mvc.perform(authed(get("/api/v1/contracts/ending").param("from", LocalDate.now().toString())
                        .param("to", LocalDate.now().plusDays(90).toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].partnerName").value("Vermieter GmbH"));
    }

    // ------------------------------------------------------------------ people, mail, templates, automations

    @Test
    @Order(40)
    @DisplayName("contacts, mail, templates and automations are created and listed")
    void organisation() throws Exception {
        mvc.perform(authed(post("/api/v1/contacts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Finanzamt Mitte\",\"kind\":\"AUTHORITY\",\"email\":\"post@fa.example\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("AUTHORITY"));
        mvc.perform(authed(get("/api/v1/contacts").param("kind", "AUTHORITY").param("q", "finanz")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].display_name").value("Finanzamt Mitte"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(authed(get("/api/v1/contacts").param("kind", "PERSON")))
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(authed(get("/api/v1/mail/messages").param("folder", "INBOX", "SENT", "DRAFTS")
                        .param("status", "NEW", "PROCESSED", "NEEDS_REPLY")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());

        MvcResult template = mvc.perform(authed(post("/api/v1/templates")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"kuendigung-%s","nameGerman":"Kündigung \\"Strom\\"","category":"TERMINATION",
                                 "bodyHtml":"<p>Hiermit kündige ich {{vertragsnummer}}.</p>",
                                 "fields":[{"fieldKey":"vertragsnummer","labelGerman":"vertragsnummer",
                                            "sourceEntity":"CUSTOM","valueType":"TEXT","required":false}]}
                                """.formatted(UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isCreated()).andReturn();
        assertThat(JsonPath.<String>read(template.getResponse().getContentAsString(), "$.templateId")).isNotBlank();
        mvc.perform(authed(get("/api/v1/templates").param("status", "DRAFT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Kündigung \"Strom\""))
                .andExpect(jsonPath("$.content[0].status").value("DRAFT"));

        MvcResult workflow = mvc.perform(authed(post("/api/v1/workflows")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"eingang-%s\",\"name\":\"Eingang prüfen\",\"category\":\"DOCUMENT\",\"triggerConfig\":{\"type\":\"documentReceived\"}}"
                                .formatted(UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().is2xxSuccessful()).andReturn();
        assertThat(JsonPath.<String>read(workflow.getResponse().getContentAsString(), "$.definitionId")).isNotBlank();
        mvc.perform(authed(get("/api/v1/workflows")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trigger_type").value("documentReceived"))
                .andExpect(jsonPath("$[0].runs_30d").isNumber());
    }

    @Test
    @Order(41)
    @DisplayName("members: the list carries email and role, an invitation can name a role, a role can change")
    void members() throws Exception {
        mvc.perform(authed(get("/api/v1/members")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].user_id").value(userId))
                .andExpect(jsonPath("$[0].email").value(email))
                .andExpect(jsonPath("$[0].role_codes").value("OWNER"));

        mvc.perform(authed(post("/api/v1/invitations")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"kollegin-%s@example.de\",\"roleCode\":\"ACCOUNTANT\"}"
                                .formatted(UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isCreated());
        mvc.perform(authed(get("/api/v1/invitations"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists()).andExpect(jsonPath("$[0].email").exists());
        mvc.perform(authed(post("/api/v1/invitations")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.de\",\"roleCode\":\"NOPE\"}"))
                .andExpect(status().isBadRequest());

        // The last owner keeps the ownership: demoting them is refused.
        mvc.perform(authed(post("/api/v1/members/" + userId + "/role")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"ADMIN\"}"))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ system and settings

    @Test
    @Order(50)
    @DisplayName("dashboard, notifications, audit, integrations, devices and reports answer for the owner")
    void system() throws Exception {
        mvc.perform(authed(get("/api/v1/dashboard/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.counts.pendingDocuments").isNumber())
                .andExpect(jsonPath("$.counts.todayTasks").isNumber())
                .andExpect(jsonPath("$.counts.invoicesToReview").isNumber())
                .andExpect(jsonPath("$.activity").isArray())
                .andExpect(jsonPath("$.automationStatus").isArray())
                .andExpect(jsonPath("$.integrationStatus").isArray());

        mvc.perform(authed(get("/api/v1/notifications"))).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(authed(get("/api/v1/notifications/unread-count"))).andExpect(jsonPath("$.unread").isNumber());
        mvc.perform(authed(post("/api/v1/notifications/read-all")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marked").isNumber());
        mvc.perform(authed(put("/api/v1/notifications/preferences")).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"category\":\"TASK\",\"channel\":\"EMAIL\",\"enabled\":true,\"frequency\":\"DAILY_DIGEST\"},"
                                + "{\"category\":\"DEADLINE\",\"channel\":\"PUSH\",\"enabled\":false,\"frequency\":\"NEVER\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.category == 'TASK' && @.channel == 'EMAIL')].frequency")
                        .value("DAILY_DIGEST"));
        mvc.perform(authed(put("/api/v1/notifications/preferences")).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"category\":\"NOPE\",\"channel\":\"EMAIL\",\"enabled\":true}]"))
                .andExpect(status().isBadRequest());

        mvc.perform(authed(get("/api/v1/audit/events").param("limit", "20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].eventId").exists())
                .andExpect(jsonPath("$.events[0].createdAt").exists());
        mvc.perform(authed(get("/api/v1/integrations"))).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(authed(get("/api/v1/devices"))).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(authed(get("/api/v1/reports/company").param("from", LocalDate.now().withDayOfMonth(1).toString())
                        .param("to", LocalDate.now().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metrics[0].label").exists())
                .andExpect(jsonPath("$.metrics[0].value").exists());
    }

    @Test
    @Order(51)
    @DisplayName("settings: accessibility is stored and validated; security shows sessions; the password changes")
    void settings() throws Exception {
        mvc.perform(authed(get("/api/v1/settings/accessibility")))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(authed(put("/api/v1/settings/accessibility")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"dark\",\"contrast\":\"high\",\"textScale\":112,\"motion\":\"reduced\"}"))
                .andExpect(status().isOk());
        mvc.perform(authed(get("/api/v1/settings/accessibility")))
                .andExpect(jsonPath("$.theme").value("dark")).andExpect(jsonPath("$.textScale").value(112));
        mvc.perform(authed(put("/api/v1/settings/accessibility")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"neon\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(put("/api/v1/settings/accessibility")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"textScale\":300}"))
                .andExpect(status().isBadRequest());

        mvc.perform(authed(get("/api/v1/settings/security")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false))
                .andExpect(jsonPath("$.sessions[0].current").value(true))
                .andExpect(jsonPath("$.sessions[0].device").exists())
                .andExpect(jsonPath("$.policy.passwordMinLength").isNumber());

        mvc.perform(authed(post("/api/v1/settings/security/password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"falsch-falsch-123\",\"newPassword\":\"Ein-Anderes-Passwort-2027!\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(authed(post("/api/v1/settings/security/password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"kurz\"}".formatted(PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("newPassword"));
        mvc.perform(authed(post("/api/v1/settings/security/password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"Ein-Anderes-Passwort-2027!\"}"
                                .formatted(PASSWORD)))
                .andExpect(status().isNoContent());
        // The session that changed the password stays valid.
        mvc.perform(authed(get("/api/v1/auth/me"))).andExpect(status().isOk());
    }

    @Test
    @Order(52)
    @DisplayName("assistant: without matching documents the answer says there is no data and names no source")
    void assistant() throws Exception {
        mvc.perform(authed(post("/api/v1/ai/assistant/query")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Wie hoch ist die Kaution im Mietvertrag Zzyzx?\",\"mode\":\"question\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("NO_DATA"))
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.model").value("none"))
                .andExpect(jsonPath("$.disclaimerRequired").value(false));
        mvc.perform(authed(post("/api/v1/ai/assistant/query")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(60)
    @DisplayName("every one of these calls is refused without a session")
    void unauthenticated() throws Exception {
        for (String path : new String[] {"/api/v1/dashboard/summary", "/api/v1/contacts", "/api/v1/settings/security",
                "/api/v1/calendar/events?from=2026-01-01&to=2026-01-31", "/api/v1/mail/messages", "/api/v1/templates",
                "/api/v1/banking/transactions", "/api/v1/invoices", "/api/v1/members"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
        return request.cookie(session, xsrfCookie).header("X-XSRF-TOKEN", xsrfCookie.getValue());
    }
}

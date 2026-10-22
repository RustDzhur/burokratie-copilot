package de.buerokratiecopilot.documents.zuordnung;

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
import java.util.concurrent.ThreadLocalRandom;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

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

import de.buerokratiecopilot.contacts.identifiers.IdentifierKind;
import de.buerokratiecopilot.contacts.identifiers.IdentifierNormalizer;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * The client routing of the Kanzlei edition (§5.2), through HTTP, the service and a real PostgreSQL.
 *
 * <p>These are the cases in which a wrong answer sends one client's paperwork into another client's books:
 * a strong identifier, two equally strong candidates, a name that is only a hint, learning from a person,
 * the account boundary, an assignment that already exists, and the rule that was deleted. A mock cannot
 * show any of them - the row level security policy, the audit trail and the threshold all live in the
 * database and in the existing services.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class MandantRoutingServiceIT {

    /** A valid German IBAN used throughout: wrong check digits would make the matcher ignore it. */
    private static final String IBAN = "DE89370400440532013000";
    private static final String IBAN_SPELLED = "DE89 3704 0044 0532 0130 00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MandantRoutingService routing;

    private record Account(Cookie cookie, UUID tenantId, UUID userId) {
    }

    // ------------------------------------------------------------------ setup

    private Account register() throws Exception {
        String email = "routing-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Routing Test","accountType":"COMPANY"}
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

    private UUID contact(Account account, String name, String email) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (tenant_id, contact_type, display_name, organization_name, email)
                values (?, 'ORGANIZATION', ?, ?, ?)
                returning id
                """, UUID.class, account.tenantId(), name, name, email));
    }

    private void identifier(Account account, UUID contactId, IdentifierKind kind, String value) {
        String normalized = IdentifierNormalizer.normalize(kind, value);
        RequestContext.runAs(account.tenantId(), () -> jdbc.update("""
                insert into contact_identifiers (tenant_id, contact_id, kind, value, normalized_value)
                values (?, ?, ?, ?, ?)
                """, account.tenantId(), contactId, kind.name(), value, normalized));
    }

    private UUID document(Account account, UUID mandantId, String sender, String senderEmail, String text) {
        String details = senderEmail == null ? "{}" : "{\"email\":\"" + senderEmail + "\"}";
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into documents (tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status, beleg_status,
                                       mandant_id, sender, sender_details, extracted_text)
                values (?, 'beleg.pdf', 'beleg.pdf', 'application/pdf', 10, ?, 'UPLOAD', 'READY',
                        'NOT_REQUIRED', 'NEU', ?, ?, cast(? as jsonb), ?)
                returning id
                """, UUID.class, account.tenantId(),
                "%064x".formatted(ThreadLocalRandom.current().nextLong()), mandantId, sender, details, text));
    }

    // ------------------------------------------------------------------ calls

    private MvcResult patchMandant(Account account, UUID documentId, UUID mandantId) throws Exception {
        return mockMvc.perform(patch("/api/v1/documents/{id}/mandant", documentId).with(csrf())
                        .cookie(account.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mandantId\":\"%s\"}".formatted(mandantId)))
                .andReturn();
    }

    private MvcResult reroute(Account account, UUID documentId) throws Exception {
        return mockMvc.perform(post("/api/v1/documents/{id}/mandant-reroute", documentId).with(csrf())
                        .cookie(account.cookie()))
                .andReturn();
    }

    private List<Map<String, Object>> rules(Account account) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/settings/mandant-rules").cookie(account.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(body(result), "$.items");
    }

    private int deleteRule(Account account, String ruleId) throws Exception {
        return mockMvc.perform(delete("/api/v1/settings/mandant-rules/{id}", ruleId).with(csrf())
                .cookie(account.cookie())).andReturn().getResponse().getStatus();
    }

    private MandantRoutingService.RoutingResult route(Account account, UUID documentId) {
        return RequestContext.runAs(account.tenantId(), () -> routing.route(documentId));
    }

    private Map<String, Object> storedRouting(Account account, UUID documentId) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForMap("""
                select mandant_id, zuordnungs_status, mandant_confidence
                  from documents where id = ? and tenant_id = ?
                """, documentId, account.tenantId()));
    }

    // ------------------------------------------------------------------ one strong signal

    @Test
    @DisplayName("an IBAN on the voucher assigns its client, records AUTO and writes an automatic rule")
    void strongIbanAssignsTheClient() throws Exception {
        Account account = register();
        UUID client = contact(account, "ACME Buchhaltung GmbH", null);
        identifier(account, client, IdentifierKind.IBAN, IBAN);
        UUID document = document(account, null, null, null,
                "Rechnung 2026-17\nIBAN: " + IBAN_SPELLED + "\nBetrag: 1.190,00 EUR");

        MvcResult result = reroute(account, document);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = body(result);
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("ASSIGNED");
        assertThat((String) JsonPath.read(body, "$.mandantId")).isEqualTo(client.toString());
        assertThat(((Number) JsonPath.read(body, "$.confidence")).doubleValue()).isEqualTo(0.95);
        assertThat((List<String>) JsonPath.read(body, "$.signals[*].type")).contains("IBAN");
        assertThat((List<String>) JsonPath.read(body, "$.candidates[*].mandantId"))
                .containsExactly(client.toString());

        Map<String, Object> stored = storedRouting(account, document);
        assertThat(stored.get("mandant_id")).isEqualTo(client);
        assertThat(stored.get("zuordnungs_status")).isEqualTo("AUTO");
        assertThat(((java.math.BigDecimal) stored.get("mandant_confidence")).doubleValue()).isEqualTo(0.95);

        // The automatic decision is auditable with its origin and its evidence.
        List<String> audit = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForList("""
                select description from audit_events
                 where tenant_id = ? and action = 'DOCUMENT_MANDANT_ASSIGNED'
                """, String.class, account.tenantId()));
        assertThat(audit).singleElement().satisfies(description -> assertThat(description)
                .contains("AUTO").contains("IBAN"));

        // The signal that decided is remembered for the next voucher. Deleting it is one click (§5.2).
        List<Map<String, Object>> rules = rules(account);
        assertThat(rules).singleElement().satisfies(rule -> {
            assertThat(rule.get("signalTyp")).isEqualTo("IBAN");
            assertThat(rule.get("mandantId")).isEqualTo(client.toString());
            assertThat(rule.get("quelle")).isEqualTo("AUTO");
            assertThat(rule.get("treffer")).isEqualTo(1);
            assertThat(rule.get("letzteNutzung")).isNotNull();
        });
    }

    // ------------------------------------------------------------------ no decision

    @Test
    @DisplayName("two equally strong candidates leave the voucher without a client and say ambiguous")
    void twoEqualCandidatesAreAmbiguous() throws Exception {
        Account account = register();
        UUID byEmail = contact(account, "Alpha Verwaltung GmbH", "buchhaltung@alpha.example");
        UUID byIban = contact(account, "Beta Handel GmbH", null);
        identifier(account, byIban, IdentifierKind.IBAN, IBAN);
        UUID document = document(account, null, null, "Buchhaltung@Alpha.Example",
                "Rechnung\nIBAN: " + IBAN_SPELLED);

        MvcResult result = reroute(account, document);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(body(result), "$.status")).isEqualTo("AMBIGUOUS");
        assertThat((List<String>) JsonPath.read(body(result), "$.candidates[*].mandantId"))
                .containsExactlyInAnyOrder(byEmail.toString(), byIban.toString());

        Map<String, Object> stored = storedRouting(account, document);
        assertThat(stored.get("mandant_id")).as("nobody, not the alphabetically first").isNull();
        assertThat(stored.get("zuordnungs_status")).isEqualTo("UNKLAR");
        assertThat(stored.get("mandant_confidence")).isNull();
        assertThat(rules(account)).as("no rule from an undecided document").isEmpty();
    }

    @Test
    @DisplayName("a company name alone is below the threshold and does not assign")
    void weakSignalDoesNotAssign() throws Exception {
        Account account = register();
        contact(account, "Musterfirma Handel GmbH", null);
        UUID document = document(account, null, "Musterfirma Handel GmbH", null,
                "Musterfirma Handel GmbH\nRechnung 2026-18\nBetrag: 49,00 EUR");

        MvcResult result = reroute(account, document);

        assertThat((String) JsonPath.read(body(result), "$.status")).isEqualTo("BELOW_THRESHOLD");
        assertThat(((Number) JsonPath.read(body(result), "$.candidates[0].confidence")).doubleValue())
                .isEqualTo(0.80);
        assertThat(storedRouting(account, document).get("mandant_id")).isNull();
        assertThat(storedRouting(account, document).get("zuordnungs_status")).isEqualTo("UNKLAR");
        assertThat(rules(account)).isEmpty();
    }

    // ------------------------------------------------------------------ learning

    @Test
    @DisplayName("a manual assignment teaches e-mail, name and IBAN; the next similar voucher is assigned with 0.95")
    void manualAssignmentLearnsAndRoutesTheNextDocument() throws Exception {
        Account account = register();
        UUID client = contact(account, "ACME Buchhaltung GmbH", null);
        UUID first = document(account, null, "ACME Buchhaltung GmbH", "rechnung@acme-buchhaltung.example",
                "Rechnung\nIBAN: " + IBAN_SPELLED);

        MvcResult patched = patchMandant(account, first, client);
        assertThat(patched.getResponse().getStatus()).isEqualTo(200);
        assertThat(storedRouting(account, first).get("mandant_id")).isEqualTo(client);
        assertThat(storedRouting(account, first).get("zuordnungs_status")).isEqualTo("MANUELL");

        List<Map<String, Object>> rules = rules(account);
        assertThat(rules).extracting(rule -> rule.get("signalTyp"))
                .containsExactlyInAnyOrder("ABSENDER_EMAIL", "FIRMENNAME", "IBAN");
        assertThat(rules).allSatisfy(rule -> {
            assertThat(rule.get("quelle")).isEqualTo("MANUELL");
            assertThat(rule.get("mandantId")).isEqualTo(client.toString());
            assertThat(rule.get("treffer")).isEqualTo(0);
        });

        // The next letter from the same sender has only the e-mail; the learned rule has to carry it.
        UUID second = document(account, null, null, "rechnung@acme-buchhaltung.example",
                "Rechnung ohne weitere Angaben");

        MandantRoutingService.RoutingResult result = route(account, second);

        assertThat(result.status()).isEqualTo(MandantRoutingService.Status.ASSIGNED);
        assertThat(result.mandantId()).isEqualTo(client);
        assertThat(result.confidence()).as("the learned rule is the 0.95 of §5.2").isEqualTo(0.95);
        assertThat(storedRouting(account, second).get("mandant_id")).isEqualTo(client);
        assertThat(storedRouting(account, second).get("zuordnungs_status")).isEqualTo("AUTO");

        List<Map<String, Object>> used = rules(account);
        assertThat(used).filteredOn(rule -> "ABSENDER_EMAIL".equals(rule.get("signalTyp")))
                .singleElement().satisfies(rule -> {
                    assertThat(rule.get("treffer")).isEqualTo(1);
                    assertThat(rule.get("letzteNutzung")).isNotNull();
                    assertThat(rule.get("quelle")).as("an automatic use does not overwrite the origin")
                            .isEqualTo("MANUELL");
                });
        // The automatic run did not create a second rule for the same signal.
        assertThat(used).hasSize(3);
    }

    @Test
    @DisplayName("a learned rule is invisible to another account, which therefore assigns nothing")
    void learnedRuleDoesNotCrossTheAccountBoundary() throws Exception {
        Account owner = register();
        UUID ownerClient = contact(owner, "ACME Buchhaltung GmbH", null);
        UUID ownerDocument = document(owner, null, null, "rechnung@acme-buchhaltung.example", "Rechnung");
        assertThat(patchMandant(owner, ownerDocument, ownerClient).getResponse().getStatus()).isEqualTo(200);
        List<Map<String, Object>> ownerRules = rules(owner);
        assertThat(ownerRules).hasSize(1);
        String ruleId = (String) ownerRules.getFirst().get("id");

        Account stranger = register();
        contact(stranger, "Fremde Firma GmbH", null);
        UUID strangerDocument = document(stranger, null, null, "rechnung@acme-buchhaltung.example",
                "Rechnung");

        assertThat(rules(stranger)).isEmpty();
        assertThat(route(stranger, strangerDocument).status())
                .isEqualTo(MandantRoutingService.Status.NO_SIGNALS);
        assertThat(storedRouting(stranger, strangerDocument).get("mandant_id")).isNull();

        // Another account's rule is not found, not forbidden: the answer must not confirm that it exists.
        assertThat(deleteRule(stranger, ruleId)).isEqualTo(404);
        assertThat(deleteRule(owner, ruleId)).isEqualTo(204);
        assertThat(rules(owner)).isEmpty();
    }

    // ------------------------------------------------------------------ safety

    @Test
    @DisplayName("an existing assignment is never overwritten, even by a stronger identifier")
    void existingAssignmentIsNeverOverwritten() throws Exception {
        Account account = register();
        UUID first = contact(account, "Alpha Verwaltung GmbH", null);
        UUID second = contact(account, "Beta Handel GmbH", null);
        identifier(account, second, IdentifierKind.IBAN, IBAN);
        UUID document = document(account, first, null, null, "IBAN: " + IBAN_SPELLED);
        RequestContext.runAs(account.tenantId(), () -> jdbc.update("""
                update documents set zuordnungs_status = 'MANUELL' where id = ?
                """, document));

        MvcResult result = reroute(account, document);

        assertThat((String) JsonPath.read(body(result), "$.status")).isEqualTo("ALREADY_ASSIGNED");
        assertThat((String) JsonPath.read(body(result), "$.mandantId")).isEqualTo(first.toString());
        Map<String, Object> stored = storedRouting(account, document);
        assertThat(stored.get("mandant_id")).as("the person's earlier decision stays").isEqualTo(first);
        assertThat(stored.get("zuordnungs_status")).isEqualTo("MANUELL");
        assertThat(rules(account)).isEmpty();
    }

    @Test
    @DisplayName("deleting the learned rule stops the automatic assignment it enabled")
    void deletingTheRuleStopsAutomaticAssignment() throws Exception {
        Account account = register();
        UUID client = contact(account, "ACME Buchhaltung GmbH", null);
        UUID first = document(account, null, null, "rechnung@acme-buchhaltung.example", "Rechnung");
        assertThat(patchMandant(account, first, client).getResponse().getStatus()).isEqualTo(200);

        List<Map<String, Object>> rules = rules(account);
        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst().get("signalTyp")).isEqualTo("ABSENDER_EMAIL");
        String ruleId = (String) rules.getFirst().get("id");

        UUID second = document(account, null, null, "rechnung@acme-buchhaltung.example", "Rechnung");
        assertThat(route(account, second).status()).isEqualTo(MandantRoutingService.Status.ASSIGNED);
        assertThat(storedRouting(account, second).get("mandant_id")).isEqualTo(client);

        assertThat(deleteRule(account, ruleId)).isEqualTo(204);
        assertThat(rules(account)).isEmpty();

        UUID third = document(account, null, null, "rechnung@acme-buchhaltung.example", "Rechnung");
        assertThat(route(account, third).status()).isEqualTo(MandantRoutingService.Status.NO_SIGNALS);
        assertThat(storedRouting(account, third).get("mandant_id")).as("the knowledge is gone").isNull();
    }
}

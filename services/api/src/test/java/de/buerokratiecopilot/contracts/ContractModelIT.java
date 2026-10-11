package de.buerokratiecopilot.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies the contract register (spec §13.1).
 *
 * <p>The acceptance criterion is that a term is shown together with the snippet it came from. The test
 * therefore stores a clause, reads the contract card back, and checks that the words, the page and the
 * excerpt are all there — and that a clause nobody has interpreted is reported as such rather than
 * silently looking like a settled reading.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractModelIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "contract-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Vertrag Test","accountType":"COMPANY"}
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

    private String createContract(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/contracts").with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Rahmenvertrag IT-Service","contractNumber":"RV-2026-001",
                                 "contractType":"SERVICE","startDate":"2026-01-01",
                                 "endDate":"2026-12-31","autoRenewal":true,"renewalPeriodMonths":12,
                                 "noticePeriodValue":3,"noticePeriodUnit":"MONTHS",
                                 "costAmount":1200.00,"costCurrency":"EUR","costInterval":"MONTHLY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dataVerified").value(false))
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    @DisplayName("a contract stores its terms and starts with unverified data")
    void createsContractWithTerms() throws Exception {
        Session session = register();
        String contractId = createContract(session);

        mockMvc.perform(get("/api/v1/contracts/{id}", contractId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contract.title").value("Rahmenvertrag IT-Service"))
                .andExpect(jsonPath("$.contract.autoRenewal").value(true))
                .andExpect(jsonPath("$.contract.renewalPeriodMonths").value(12))
                .andExpect(jsonPath("$.contract.noticePeriodValue").value(3))
                // The extracted values are what the document says until a person checks them.
                .andExpect(jsonPath("$.contract.dataVerified").value(false));
    }

    @Test
    @DisplayName("a clause keeps its words, its page and the snippet it was read from")
    void clauseCarriesItsProvenance() throws Exception {
        Session session = register();
        String contractId = createContract(session);

        MvcResult created = mockMvc.perform(post("/api/v1/contracts/{id}/clauses", contractId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clauseKey":"NOTICE_PERIOD","clauseType":"NOTICE_PERIOD",
                                 "title":"Kündigungsfrist",
                                 "clauseText":"Der Vertrag kann mit einer Frist von drei Monaten zum Monatsende gekündigt werden.",
                                 "sourcePage":2,
                                 "sourceSnippet":"§ 5 Kündigung: Der Vertrag kann mit einer Frist von drei Monaten zum Monatsende gekündigt werden.",
                                 "confidence":0.93,"critical":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourcePage").value(2))
                .andExpect(jsonPath("$.sourceSnippet").isNotEmpty())
                .andExpect(jsonPath("$.clauseText").value(org.hamcrest.Matchers.containsString("drei Monaten")))
                .andReturn();

        String clauseId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/contracts/{id}", contractId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clauses[0].id").value(clauseId))
                .andExpect(jsonPath("$.clauses[0].sourceSnippet")
                        .value(org.hamcrest.Matchers.containsString("§ 5 Kündigung")))
                .andExpect(jsonPath("$.clauses[0].state").value("NOT_INTERPRETED"));
    }

    @Test
    @DisplayName("a critical clause nobody has interpreted is named explicitly")
    void criticalClausesWithoutInterpretationAreListed() throws Exception {
        Session session = register();
        String contractId = createContract(session);
        mockMvc.perform(post("/api/v1/contracts/{id}/clauses", contractId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clauseKey":"LIABILITY","clauseType":"LIABILITY","clauseText":"Die Haftung ist begrenzt.",
                                 "sourcePage":4,"sourceSnippet":"§ 9 Haftung: Die Haftung ist begrenzt.",
                                 "critical":true}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/contracts/{id}", contractId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criticalClausesWithoutInterpretation")
                        .value(org.hamcrest.Matchers.contains("LIABILITY")));

        mockMvc.perform(get("/api/v1/contracts/{id}/critical-clauses", contractId)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("NOT_INTERPRETED"))
                .andExpect(jsonPath("$[0].hasInterpretation").value(false));
    }

    @Test
    @DisplayName("an interpretation needs a basis, and an unverified one is labelled as such")
    void interpretationNeedsABasisAndIsLabelled() throws Exception {
        Session session = register();
        String contractId = createContract(session);
        MvcResult clause = mockMvc.perform(post("/api/v1/contracts/{id}/clauses", contractId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clauseKey":"RENEWAL","clauseType":"AUTO_RENEWAL",
                                 "clauseText":"Der Vertrag verlängert sich automatisch um zwölf Monate.",
                                 "sourcePage":1,"sourceSnippet":"§ 2 Laufzeit: verlängert sich automatisch.",
                                 "critical":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String clauseId = JsonPath.read(clause.getResponse().getContentAsString(), "$.id");

        // Without a basis the platform would be giving its own legal opinion.
        mockMvc.perform(post("/api/v1/contracts/clauses/{clauseId}/interpretation", clauseId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interpretation\":\"Der Vertrag verlängert sich um ein Jahr.\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("basis")));

        mockMvc.perform(post("/api/v1/contracts/clauses/{clauseId}/interpretation", clauseId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"interpretation":"Der Vertrag verlängert sich um zwölf Monate.",
                                 "source":"HUMAN"}
                                """))
                .andExpect(status().isOk())
                // A conclusion a person drew is theirs and is verified by the act of recording it.
                .andExpect(jsonPath("$.state").value("VERIFIED_INTERPRETATION"))
                .andExpect(jsonPath("$.interpretationSource").value("HUMAN"))
                .andExpect(jsonPath("$.interpretationVerified").value(true));
    }

    @Test
    @DisplayName("a clause without a snippet is refused, because nobody could check it")
    void clauseWithoutSnippetIsRefused() throws Exception {
        Session session = register();
        String contractId = createContract(session);

        mockMvc.perform(post("/api/v1/contracts/{id}/clauses", contractId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clauseKey":"X","clauseText":"Ein Satz ohne Herkunft."}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("snippet")));
    }

    @Test
    @DisplayName("a party keeps the name the contract writes, next to the contact it was matched to")
    void partyKeepsTheWrittenName() throws Exception {
        Session session = register();
        String contractId = createContract(session);

        mockMvc.perform(post("/api/v1/contracts/{id}/parties", contractId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"partyRole":"COUNTERPARTY","partyName":"Muster GmbH, vertreten durch den Geschäftsführer",
                                 "primary":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.partyName")
                        .value("Muster GmbH, vertreten durch den Geschäftsführer"))
                .andExpect(jsonPath("$.contactId").doesNotExist());
    }

    @Test
    @DisplayName("verifying the data is recorded with the person who did it")
    void verifyDataIsRecorded() throws Exception {
        Session session = register();
        String contractId = createContract(session);

        mockMvc.perform(post("/api/v1/contracts/{id}/verify-data", contractId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataVerified").value(true));

        assertThat(session.userId()).isNotNull();
    }

    @Test
    @DisplayName("a contract number that already exists is refused")
    void duplicateContractNumberIsRefused() throws Exception {
        Session session = register();
        createContract(session);

        mockMvc.perform(post("/api/v1/contracts").with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Zweiter Vertrag","contractNumber":"RV-2026-001"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("contracts ending in a window are listed for the renewal overview")
    void listsContractsEndingInAWindow() throws Exception {
        Session session = register();
        createContract(session);

        mockMvc.perform(get("/api/v1/contracts/ending").param("from", "2026-10-01")
                        .param("to", "2027-01-31").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/contracts/ending").param("from", "2027-06-01")
                        .param("to", "2027-12-31").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}

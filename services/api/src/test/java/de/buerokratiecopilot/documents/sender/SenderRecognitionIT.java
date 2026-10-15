package de.buerokratiecopilot.documents.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.nio.charset.StandardCharsets;
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

import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.shared.context.RequestContext;

/** The document page shows the risks that cite the document and the cases it belongs to (S02-14). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SenderRecognitionIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DocumentProcessingPipeline pipeline;

    private record Session(Cookie cookie, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "ins-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        String body = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"P\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(body, "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andReturn().getResponse().getCookie("bc_session");
        return new Session(cookie, userId);
    }

    private static final String LETTER = """
            Stadtwerke Musterstadt GmbH
            Hauptstraße 12, 12345 Musterstadt
            Telefon: 0123 / 456 789-0
            E-Mail: service@stadtwerke-musterstadt.example
            USt-IdNr.: DE123456789

            Rechnung
            Rechnungsnummer: RE-%s
            Rechnungsdatum: 01.10.2026
            Strom Abschlag                       80,67 EUR
            Umsatzsteuer 19 %%:                  15,33 EUR
            Bruttobetrag:                        96,00 EUR
            Zahlbar bis 20.10.2026
            """;

    private UUID uploadAndProcess(Session s, String text) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "Brief.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)))
                .with(csrf()).cookie(s.cookie())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.uploadedByName").value("P")).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.document.id"));
        UUID tenant = jdbc.queryForObject("select tenant_id from documents where id = ?", UUID.class, id);
        RequestContext.runAs(tenant, () -> pipeline.advance(id));
        return id;
    }

    @Test
    @DisplayName("the sender is read from the letter, kept with the document and as one contact that the next letter of the same sender reuses")
    void senderIsReadAndLinked() throws Exception {
        Session me = register();
        UUID first = uploadAndProcess(me, LETTER.formatted("0001"));
        UUID second = uploadAndProcess(me, LETTER.formatted("0002"));

        String shown = mockMvc.perform(get("/api/v1/documents/" + first).cookie(me.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.sender").value("Stadtwerke Musterstadt GmbH"))
                .andExpect(jsonPath("$.senderSource").value("LETTER"))
                .andExpect(jsonPath("$.senderDetails.address").value("Hauptstraße 12, 12345 Musterstadt"))
                .andExpect(jsonPath("$.senderDetails.phone").value("0123 / 456 789-0"))
                .andExpect(jsonPath("$.senderDetails.email").value("service@stadtwerke-musterstadt.example"))
                .andExpect(jsonPath("$.senderDetails.vatId").value("DE123456789"))
                .andExpect(jsonPath("$.uploadedByName").value("P")).andReturn().getResponse().getContentAsString();
        UUID contact = UUID.fromString(JsonPath.read(shown, "$.senderContactId"));

        UUID tenant = jdbc.queryForObject("select tenant_id from documents where id = ?", UUID.class, first);
        RequestContext.runAs(tenant, () -> {
            assertThat(jdbc.queryForObject("select sender_contact_id from documents where id = ?", UUID.class, second)).isEqualTo(contact);
            assertThat(jdbc.queryForObject("select count(*) from contacts where tenant_id = ? and organization_name = 'Stadtwerke Musterstadt GmbH'",
                    Integer.class, tenant)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select city from contact_addresses where contact_id = ?", String.class, contact)).isEqualTo("Musterstadt");
            assertThat(jdbc.queryForObject("select phone from contacts where id = ?", String.class, contact)).isEqualTo("0123 / 456 789-0");
            return null;
        });

        // The register finds the letter by the sender's name.
        mockMvc.perform(get("/api/v1/documents?q=stadtwerke").cookie(me.cookie())).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("a sender a person set is never overwritten, and a letter that does not say who sent it keeps an empty sender")
    void personsChoiceStays() throws Exception {
        Session me = register();
        UUID id = uploadAndProcess(me, "Arztbrief\nSehr geehrte Frau Kollegin, wir berichten über die Behandlung.\nDiagnose: Bronchitis");
        mockMvc.perform(get("/api/v1/documents/" + id).cookie(me.cookie())).andExpect(jsonPath("$.sender").doesNotExist());

        UUID tenant = jdbc.queryForObject("select tenant_id from documents where id = ?", UUID.class, id);
        RequestContext.runAs(tenant, () -> jdbc.update("update documents set sender = 'Dr. Beispiel', sender_source = 'PERSON' where id = ?", id));
        UUID other = uploadAndProcess(me, LETTER.formatted("0003"));
        RequestContext.runAs(tenant, () -> {
            assertThat(jdbc.queryForObject("select sender from documents where id = ?", String.class, id)).isEqualTo("Dr. Beispiel");
            assertThat(jdbc.queryForObject("select sender from documents where id = ?", String.class, other)).isEqualTo("Stadtwerke Musterstadt GmbH");
            return null;
        });
    }
}

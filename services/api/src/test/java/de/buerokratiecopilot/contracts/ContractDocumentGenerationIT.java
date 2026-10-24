package de.buerokratiecopilot.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.support.TenantContextExtension;
import de.buerokratiecopilot.templates.TemplateService;

/**
 * A contract generates its document from a CONTRACT template (spec §13, V72).
 *
 * <p>The acceptance is end to end: the render happens with the contract's own data, the PDF and the DOCX
 * are produced and stored as documents of the account, the generated records point at the files, and a new
 * current version binds the PDF to the contract — so the contract page can show a document that exists
 * instead of a record describing one that does not.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class ContractDocumentGenerationIT {

    @Autowired
    private ContractDocumentService documents;

    @Autowired
    private TemplateService templates;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    // ------------------------------------------------------------------ test data

    private UUID tenant() {
        UUID id = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Vertrag Vorlagen Test', 'COMPANY') returning id
                """, UUID.class, "cv-" + UUID.randomUUID().toString().substring(0, 12));
        RequestContext.setTenantId(id);
        return id;
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "cd-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    private UUID contract(UUID tenantId, UUID workspaceId, UUID organizationId,
                          UUID counterpartyContactId) {
        return jdbc.queryForObject("""
                insert into contracts (id, tenant_id, workspace_id, organization_id,
                                       counterparty_contact_id, contract_number, title, contract_type,
                                       status, start_date, end_date, auto_renewal, notice_period_value,
                                       notice_period_unit, cost_amount, cost_currency, cost_interval)
                values (gen_random_uuid(), ?, ?, ?, ?, 'V-2026-001', 'Mietvertrag Lagerhalle', 'RENT',
                        'ACTIVE', '2026-01-01', '2026-12-31', true, 3, 'MONTHS', 1250.50, 'EUR', 'MONTHLY')
                returning id
                """, UUID.class, tenantId, workspaceId, organizationId, counterpartyContactId);
    }

    private UUID party(UUID tenantId, UUID contractId, String role, String name, UUID contactId,
                       UUID organizationId, boolean primary) {
        return jdbc.queryForObject("""
                insert into contract_parties (id, contract_id, tenant_id, contact_id, organization_id,
                                              party_role, party_name, is_primary)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, contractId, tenantId, contactId, organizationId, role, name, primary);
    }

    private UUID contact(UUID tenantId, String displayName, String street, String city) {
        UUID id = jdbc.queryForObject("""
                insert into contacts (id, tenant_id, contact_type, display_name, email)
                values (gen_random_uuid(), ?, 'ORGANIZATION', ?, ?) returning id
                """, UUID.class, tenantId, displayName, displayName.toLowerCase().replace(" ", ".")
                        + "@example.de");
        jdbc.update("""
                insert into contact_addresses (id, contact_id, tenant_id, address_type, street, postal_code,
                                               city, is_primary)
                values (gen_random_uuid(), ?, ?, 'MAIN', ?, '10115', ?, true)
                """, id, tenantId, street, city);
        return id;
    }

    private UUID workspace(UUID tenantId) {
        return jdbc.queryForObject("""
                insert into workspaces (id, tenant_id, name, mode)
                values (gen_random_uuid(), ?, 'Kanzlei Mitte', 'BUSINESS') returning id
                """, UUID.class, tenantId);
    }

    private UUID organization(UUID tenantId) {
        return jdbc.queryForObject("""
                insert into organizations (id, tenant_id, name, street, house_number, postal_code, city)
                values (gen_random_uuid(), ?, 'Bürokratie GmbH', 'Amtsweg', '1', '10117', 'Berlin')
                returning id
                """, UUID.class, tenantId);
    }

    private UUID contractTemplate(UUID tenantId, UUID userId, String bodyHtml, boolean requiredName) {
        UUID templateId = templates.define(new TemplateService.TemplateRequest(tenantId, "Vertrag",
                "Mietvertrag", null, "CONTRACT",
                List.of(new TemplateService.FieldRequest("vertrag_titel", "Titel", "VERTRAG", "Title",
                                "TEXT", requiredName, null),
                        new TemplateService.FieldRequest("partei_name", "Partei", "PARTEI",
                                "Gegenpartei.Name", "TEXT", false, null),
                        new TemplateService.FieldRequest("werk_name", "Werk", "WERK", "Name", "TEXT",
                                false, null)),
                bodyHtml, null, userId));
        templates.publish(tenantId, templateId, 1, userId);
        return templateId;
    }

    // ------------------------------------------------------------------ service level

    @Test
    @DisplayName("generation renders, exports both formats, stores the files and binds a current version")
    void generationProducesTheContractDocument() {
        UUID tenantId = tenant();
        UUID userId = person();
        UUID workspaceId = workspace(tenantId);
        UUID organizationId = organization(tenantId);
        UUID contactId = contact(tenantId, "Muster Logistik AG", "Hafenstraße 7", "Berlin");
        UUID contractId = contract(tenantId, workspaceId, organizationId, contactId);
        party(tenantId, contractId, "OWN_SIDE", "Bürokratie GmbH", null, organizationId, true);
        party(tenantId, contractId, "COUNTERPARTY", "Muster Logistik AG", contactId, null, false);

        UUID templateId = contractTemplate(tenantId, userId, """
                <h2>Vertrag {{Vertrag.Title}}</h2>
                <p>Zwischen {{Partei.Name}} und {{Partei.Gegenpartei.Name}}.</p>
                """, true);

        ContractDocumentService.Generated generated = documents.generate(tenantId, contractId, templateId,
                userId);

        assertThat(generated.documentId()).isNotNull();
        assertThat(generated.pdfRecordId()).isNotNull();
        assertThat(generated.docxRecordId()).isNotNull();
        assertThat(generated.usedFields()).contains("vertrag_titel", "partei_name");

        // Both formats were produced and recorded, pointing at real files.
        Integer records = jdbc.queryForObject("""
                select count(*) from generated_documents
                 where tenant_id = ? and subject_type = 'contract' and subject_id = ?
                   and rendered_blob_id is not null and rendered_document_id is not null
                """, Integer.class, tenantId, contractId);
        assertThat(records).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(distinct output_format) from generated_documents
                 where tenant_id = ? and subject_id = ?
                """, Integer.class, tenantId, contractId)).isEqualTo(2);

        // The version carries the PDF as its document and says where it came from.
        var version = jdbc.queryForMap("""
                select document_id, change_note, version_label, is_current
                  from contract_versions where contract_id = ?
                """, contractId);
        assertThat(version.get("document_id")).isEqualTo(generated.documentId());
        assertThat(version.get("change_note")).isEqualTo(ContractDocumentService.CHANGE_NOTE);
        assertThat(version.get("version_label")).isEqualTo("V1");
        assertThat(version.get("is_current")).isEqualTo(true);

        // The file itself exists as a document with a blob.
        Integer blobs = jdbc.queryForObject("""
                select count(*) from document_versions dv
                 join document_blobs b on b.id = dv.blob_id
                 where dv.document_id = ?
                """, Integer.class, generated.documentId());
        assertThat(blobs).isEqualTo(1);
    }

    @Test
    @DisplayName("a template that cannot be filled in is refused and nothing is written")
    void refusedRenderWritesNothing() {
        UUID tenantId = tenant();
        UUID userId = person();
        UUID contractId = contract(tenantId, workspace(tenantId), organization(tenantId), null);
        UUID templateId = contractTemplate(tenantId, userId, "Vertrag {{Vertrag.Title}}.", true);

        // The contract has a title, so this renders — the refusal needs a missing required value.
        jdbc.update("update contracts set title = '' where id = ?", contractId);

        assertThatThrownBy(() -> documents.generate(tenantId, contractId, templateId, userId))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode().status().value()).isEqualTo(422);
                    assertThat(ex.getMessage()).contains("vertrag_titel");
                });

        assertThat(jdbc.queryForObject(
                "select count(*) from generated_documents where tenant_id = ?", Integer.class, tenantId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from contract_versions where tenant_id = ?", Integer.class, tenantId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from documents where tenant_id = ?", Integer.class, tenantId)).isZero();
    }

    @Test
    @DisplayName("a template of another category is refused")
    void otherCategoryIsRefused() {
        UUID tenantId = tenant();
        UUID userId = person();
        UUID contractId = contract(tenantId, null, null, null);
        UUID letterId = templates.define(new TemplateService.TemplateRequest(tenantId, "Brief",
                "Brief", null, "LETTER", List.of(), "Text", null, userId));
        templates.publish(tenantId, letterId, 1, userId);

        assertThatThrownBy(() -> documents.generate(tenantId, contractId, letterId, userId))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode().status().value()).isEqualTo(422);
                    assertThat(ex.getMessage()).contains("CONTRACT");
                });
    }

    // ------------------------------------------------------------------ HTTP level

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "vertrag-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
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

    @Test
    @DisplayName("the contract page can generate and then download the version's file")
    void generateAndDownloadOverHttp() throws Exception {
        Session owner = register();
        UUID contractId = RequestContext.runAs(owner.tenantId(), () -> {
            UUID workspaceId = workspace(owner.tenantId());
            UUID organizationId = organization(owner.tenantId());
            UUID contactId = contact(owner.tenantId(), "Muster Logistik AG", "Hafenstraße 7", "Berlin");
            UUID id = contract(owner.tenantId(), workspaceId, organizationId, contactId);
            party(owner.tenantId(), id, "COUNTERPARTY", "Muster Logistik AG", contactId, null, true);
            return id;
        });
        UUID templateId = RequestContext.runAs(owner.tenantId(), () -> contractTemplate(owner.tenantId(),
                owner.userId(), "<p>Vertrag {{Vertrag.Title}} mit {{Partei.Name}}.</p>", true));

        MvcResult generated = mockMvc.perform(post("/api/v1/contracts/{id}/generate", contractId)
                        .with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"%s\"}".formatted(templateId)))
                .andExpect(status().isCreated())
                .andReturn();
        String documentId = JsonPath.read(generated.getResponse().getContentAsString(), "$.documentId");
        String versionId = JsonPath.read(generated.getResponse().getContentAsString(), "$.versionId");

        // The version list names the document, so the screen can offer the download.
        MvcResult versions = mockMvc.perform(get("/api/v1/contracts/{id}/versions", contractId)
                        .cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(versions.getResponse().getContentAsString()).contains(documentId);

        MvcResult download = mockMvc.perform(
                        get("/api/v1/contracts/versions/{versionId}/download", versionId)
                                .cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new String(download.getResponse().getContentAsByteArray(), StandardCharsets.US_ASCII))
                .startsWith("%PDF");
        assertThat(download.getResponse().getHeader("Content-Disposition"))
                .containsIgnoringCase("attachment");
    }

    @Test
    @DisplayName("a render problem answers 422 over HTTP and names what is missing")
    void renderProblemAnswers422() throws Exception {
        Session owner = register();
        UUID contractId = RequestContext.runAs(owner.tenantId(), () -> {
            UUID id = contract(owner.tenantId(), workspace(owner.tenantId()),
                    organization(owner.tenantId()), null);
            party(owner.tenantId(), id, "COUNTERPARTY", "Muster Logistik AG", null, null, true);
            // The required Vertrag.Title has no value, so the render must refuse.
            jdbc.update("update contracts set title = '' where id = ?", id);
            return id;
        });
        UUID templateId = RequestContext.runAs(owner.tenantId(), () -> contractTemplate(owner.tenantId(),
                owner.userId(), "Vertrag {{Vertrag.Title}}.", true));

        MvcResult refused = mockMvc.perform(post("/api/v1/contracts/{id}/generate", contractId)
                        .with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"%s\"}".formatted(templateId)))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        assertThat(refused.getResponse().getContentAsString()).contains("vertrag_titel");
    }
}

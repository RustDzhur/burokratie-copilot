package de.buerokratiecopilot.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The operator page and the licence endpoint it drives, answered by the real application.
 *
 * <p>The page is the only way an installer can administer a runtime without a shell, so it is
 * tested the way a browser would use it: without a session (there is none in a runtime
 * installation), with the {@code X-Runtime-Admin-Token} header, and with keys that are real HS256
 * tokens signed with a secret the installer generated for this run. The assets are tested too — a
 * page that loads but whose script is a 401 is useless.</p>
 *
 * <p>The test has its own application context because it enables the runtime admin surface and
 * licence enforcement, and points the key file at a temporary directory.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.springframework.test.context.TestPropertySource(properties = {
        // Read before the context decides which beans exist; the dynamic properties below hold
        // the values that must be unique per run.
        "burokratie.runtime.enabled=true"
})
class RuntimeAdminUiIT {

    private static final Path TEMP = Path.of(System.getProperty("java.io.tmpdir"), "bc-runtime-ui-it");
    private static final String ADMIN_TOKEN = "admin-" + UUID.randomUUID();
    private static final String LICENSE_SECRET = "licence-" + UUID.randomUUID() + UUID.randomUUID();
    private static final String INSTANCE_ID = UUID.randomUUID().toString();
    private static final String VALID_KEY = mint(INSTANCE_ID);

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void runtimeProperties(DynamicPropertyRegistry registry) throws IOException {
        Files.createDirectories(TEMP);
        Files.writeString(TEMP.resolve("instance-id"), INSTANCE_ID, StandardCharsets.UTF_8);
        Files.deleteIfExists(TEMP.resolve("license.key"));
        registry.add("burokratie.runtime.admin-token", () -> ADMIN_TOKEN);
        registry.add("burokratie.license.enforced", () -> "true");
        registry.add("burokratie.license.secret", () -> LICENSE_SECRET);
        registry.add("burokratie.license.instance-id-file",
                () -> TEMP.resolve("instance-id").toString());
        registry.add("burokratie.license.key-file",
                () -> TEMP.resolve("license.key").toString());
    }

    @AfterAll
    static void cleanUp() throws IOException {
        Files.deleteIfExists(TEMP.resolve("license.key"));
        Files.deleteIfExists(TEMP.resolve("instance-id"));
        try {
            Files.deleteIfExists(TEMP);
        }
        catch (IOException ignored) {
            // Another test run may still hold a file there; the directory is temporary anyway.
        }
    }

    // ------------------------------------------------------------------ the page

    @Test
    @Order(1)
    @DisplayName("GET /runtime-admin serves the page without a session")
    void pageIsPublicHtml() throws Exception {
        mvc.perform(get("/runtime-admin"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<title>Robots Runtime")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app.js")));

        mvc.perform(get("/runtime-admin/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }

    @Test
    @Order(2)
    @DisplayName("the page assets are served without a session")
    void assetsArePublic() throws Exception {
        mvc.perform(get("/runtime-admin/styles.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("text/css")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("prefers-color-scheme")));

        mvc.perform(get("/runtime-admin/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("text/javascript")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "X-Runtime-Admin-Token")));
    }

    // --------------------------------------------------------------- the API

    @Test
    @Order(3)
    @DisplayName("status without the admin token is 401")
    void statusRequiresAdminToken() throws Exception {
        mvc.perform(get("/api/v1/runtime/status"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/runtime/status").header("X-Runtime-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    @DisplayName("POST /license without the admin token is 401")
    void licenseRequiresAdminToken() throws Exception {
        mvc.perform(post("/api/v1/runtime/license")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenseKey\":\"" + VALID_KEY + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    @DisplayName("garbage is answered with 400 and a field-level reason, and nothing is written")
    void garbageKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/runtime/license")
                        .header("X-Runtime-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenseKey\":\"this is not a licence\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details[0].field").value("licenseKey"))
                .andExpect(jsonPath("$.details[0].code").value("MALFORMED"));

        assertThat(Files.exists(TEMP.resolve("license.key"))).isFalse();
    }

    @Test
    @Order(6)
    @DisplayName("a key for another installation is 400, not an installed licence")
    void foreignKeyIsRejected() throws Exception {
        String foreign = mint(UUID.randomUUID().toString());
        mvc.perform(post("/api/v1/runtime/license")
                        .header("X-Runtime-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenseKey\":\"" + foreign + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].code").value("WRONG_INSTANCE"));
    }

    @Test
    @Order(7)
    @DisplayName("a valid key is accepted, stored with 0600 and active in the same process")
    void validKeyIsInstalled() throws Exception {
        mvc.perform(post("/api/v1/runtime/license")
                        .header("X-Runtime-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"licenseKey\":\"" + VALID_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").value(true))
                .andExpect(jsonPath("$.state").value("LICENSE_ACTIVE"))
                .andExpect(jsonPath("$.license.state").value("LICENSE_ACTIVE"))
                .andExpect(jsonPath("$.license.instanceId").value(INSTANCE_ID));

        Path keyFile = TEMP.resolve("license.key");
        assertThat(Files.readString(keyFile, StandardCharsets.UTF_8).strip())
                .isEqualTo(VALID_KEY.strip());

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);
            assertThat(permissions).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    @Order(8)
    @DisplayName("status exposes version, instance id and the reloaded licence")
    void statusCarriesTheFieldsThePageShows() throws Exception {
        mvc.perform(get("/api/v1/runtime/status").header("X-Runtime-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.license.instanceId").value(INSTANCE_ID))
                .andExpect(jsonPath("$.license.state").value("LICENSE_ACTIVE"))
                .andExpect(jsonPath("$.adminTokenConfigured").value(true));
    }

    // -------------------------------------------------------------------- minting

    /**
     * Mints a real HS256 licence token. It is deliberately independent of the production verifier
     * (a test that signs with the code under test proves nothing about the wire format).
     */
    private static String mint(String instanceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("licenseId", "LIC-RUNTIME-UI");
        payload.put("customer", "Muster GmbH");
        payload.put("instanceId", instanceId);
        payload.put("plan", "ENTERPRISE");
        payload.put("maxAgents", 100);
        payload.put("maxRunsPerMonth", 5000);
        payload.put("features", List.of("agents", "workflow"));
        payload.put("issuedAt", Instant.now().minusSeconds(3600).getEpochSecond());
        payload.put("expiresAt", Instant.now().plusSeconds(30 * 86_400L).getEpochSecond());
        try {
            ObjectMapper mapper = new ObjectMapper();
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"BC-LICENSE\"}");
            String body = base64Url(mapper.writeValueAsString(payload));
            String signingInput = header + "." + body;
            return signingInput + "." + base64Url(sign(signingInput));
        }
        catch (Exception ex) {
            throw new IllegalStateException("Could not mint a test licence", ex);
        }
    }

    private static byte[] sign(String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(LICENSE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(String value) {
        return base64Url(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}

package de.buerokratiecopilot.documents.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;
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

import org.apache.pdfbox.Loader;

/** The document page shows the risks that cite the document and the cases it belongs to (S02-14). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentMergeIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

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

    private static byte[] png(int shade) throws Exception {
        BufferedImage image = new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 120; x++) {
            for (int y = 0; y < 160; y++) {
                image.setRGB(x, y, (shade * 40 << 16) | ((x * 2) << 8) | y);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private UUID uploadImage(Session s, String name, int shade) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", name, "image/png", png(shade))).with(csrf()).cookie(s.cookie()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.document.id"));
    }

    @Test
    @DisplayName("photos uploaded in one go are proposed as one document in the order of their numbers, and merging makes one PDF with every page and removes the single ones")
    void mergesPagesInOrder() throws Exception {
        Session me = register();
        UUID third = uploadImage(me, "IMG_0003.png", 3);
        UUID first = uploadImage(me, "IMG_0001.png", 1);
        UUID second = uploadImage(me, "IMG_0002.png", 2);

        String candidates = mockMvc.perform(get("/api/v1/documents/merge-candidates").cookie(me.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(candidates, "$[0].filenames")).containsExactly("IMG_0001.png", "IMG_0002.png", "IMG_0003.png");
        List<String> ids = JsonPath.read(candidates, "$[0].documentIds");
        assertThat(ids).containsExactly(first.toString(), second.toString(), third.toString());

        String merged = mockMvc.perform(post("/api/v1/documents/merge").with(csrf()).cookie(me.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"%s\",\"%s\",\"%s\"],\"title\":\"Antrag\"}".formatted(first, second, third)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.filename").value("Antrag.pdf")).andReturn().getResponse().getContentAsString();
        UUID mergedId = UUID.fromString(JsonPath.read(merged, "$.documentId"));

        byte[] pdf = mockMvc.perform(get("/api/v1/documents/" + mergedId + "/content?download=true").cookie(me.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(3);
        }
        String list = mockMvc.perform(get("/api/v1/documents").cookie(me.cookie())).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(list, "$.totalElements")).as("only the merged document is left").isEqualTo(1);
        mockMvc.perform(get("/api/v1/documents/" + first).cookie(me.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/merge-candidates").cookie(me.cookie())).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("one page cannot be merged, another person's pages cannot be reached, and a file that is not a photo or a PDF is refused")
    void refusals() throws Exception {
        Session me = register();
        UUID one = uploadImage(me, "a.png", 1);
        mockMvc.perform(post("/api/v1/documents/merge").with(csrf()).cookie(me.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"%s\"]}".formatted(one))).andExpect(status().isUnprocessableEntity());

        Session stranger = register();
        UUID other = uploadImage(stranger, "b.png", 2);
        mockMvc.perform(post("/api/v1/documents/merge").with(csrf()).cookie(me.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"%s\",\"%s\"]}".formatted(one, other))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/documents/" + other).cookie(stranger.cookie())).andExpect(status().isOk());

        String text = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "notiz.txt", "text/plain", "Hallo".getBytes())).with(csrf()).cookie(me.cookie()))
                .andReturn().getResponse().getContentAsString();
        UUID note = UUID.fromString(JsonPath.read(text, "$.document.id"));
        mockMvc.perform(post("/api/v1/documents/merge").with(csrf()).cookie(me.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"%s\",\"%s\"]}".formatted(one, note))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/documents/" + one).cookie(me.cookie())).andExpect(status().isOk());
    }
}

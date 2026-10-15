package de.buerokratiecopilot.documents.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.UUID;

import javax.imageio.ImageIO;

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

import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * A photograph of a page is read. Before this existed a picture was filed with no text at all ("no extractor for
 * image/jpeg"), so it could be neither classified nor searched. The test draws a page the way a phone would show it (uneven
 * light, grey paper), upright and lying on its side, and follows it through the pipeline. It needs the Tesseract program and
 * is skipped, with the reason, where there is none.
 */
@SpringBootTest(properties = { "burokratie.ocr.enabled=true", "burokratie.ocr.languages=deu+eng" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoOcrIT {

    private static final String[] LINES = {
            "Rechnung Nr. 2026-0815", "Rechnungsbetrag 119,00 EUR", "Umsatzsteuer 19,00 EUR", "Zahlbar bis 20.10.2026",
            "Stadtwerke Musterstadt GmbH", "Bitte ueberweisen Sie den Betrag auf das Konto", "IBAN DE89 3704 0044 0532 0130 00",
            "Kundennummer 4711 Vertragskonto 12345", "Vielen Dank fuer Ihr Vertrauen" };

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DocumentProcessingPipeline pipeline;

    private static final String[] RUSSIAN = {
            "ЗАКЛЮЧЕНИЕ МЕДИЦИНСКОЙ КОМИССИИ", "Ребенок: Мустерман Макс, 12.05.2018", "Комиссия провела осмотр ребенка и изучила документы",
            "Рекомендации: занятия с логопедом, обучение в обычной школе", "Диагноз и заключение врача прилагаются",
            "Подпись председателя комиссии и печать учреждения" };

    private static byte[] photo(int quarterTurns) throws Exception {
        return photo(LINES, quarterTurns);
    }

    private static byte[] photo(String[] lines, int quarterTurns) throws Exception {
        BufferedImage page = new BufferedImage(1700, 2300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(238, 236, 230), 1700, 2300, new Color(190, 188, 182)));
        g.fillRect(0, 0, 1700, 2300);
        g.setColor(new Color(35, 35, 40));
        g.setFont(new Font("SansSerif", Font.PLAIN, 54));
        int y = 260;
        for (String line : lines) {
            g.drawString(line, 160, y);
            y += 120;
        }
        g.dispose();
        BufferedImage out = page;
        for (int i = 0; i < quarterTurns; i++) {
            BufferedImage turned = new BufferedImage(out.getHeight(), out.getWidth(), BufferedImage.TYPE_INT_RGB);
            Graphics2D t = turned.createGraphics();
            t.translate(out.getHeight(), 0);
            t.rotate(Math.PI / 2);
            t.drawImage(out, 0, 0, null);
            t.dispose();
            out = turned;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "jpg", bytes);
        return bytes.toByteArray();
    }

    private void readPhoto(int quarterTurns) throws Exception {
        assumeTrue(new TesseractOcrEngine("tesseract", Duration.ofSeconds(20)).isAvailable(), "Tesseract is not installed here");
        String email = "photo-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"P\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andReturn();
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andReturn().getResponse().getCookie("bc_session");

        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "IMG_4242.jpg", "image/jpeg", photo(quarterTurns))).with(csrf()).cookie(cookie))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(JsonPath.read(upload.getResponse().getContentAsString(), "$.document.id"));
        UUID tenant = jdbc.queryForObject("select tenant_id from documents where id = ?", UUID.class, id);
        RequestContext.runAs(tenant, () -> pipeline.advance(id));

        String text = RequestContext.runAs(tenant, () -> jdbc.queryForObject("select coalesce(extracted_text, '') from documents where id = ?", String.class, id));
        assertThat(text).as("the text read from the photo").containsIgnoringCase("Rechnung").containsIgnoringCase("Zahlbar").contains("119,00");
        String type = RequestContext.runAs(tenant, () -> jdbc.queryForObject(
                "select coalesce((select t.code from document_types t where t.id = d.document_type_id), '') from documents d where d.id = ?", String.class, id));
        assertThat(type).as("the type found from the text of the photo").isEqualTo("rechnung");
    }

    @Test
    @DisplayName("an upright photo of an invoice is read and recognised as an invoice")
    void uprightPhoto() throws Exception {
        readPhoto(0);
    }

    @Test
    @DisplayName("the same photo lying on its side is turned by the reader and read just as well")
    void sidewaysPhoto() throws Exception {
        readPhoto(1);
    }

    @Test
    @DisplayName("upside down too")
    void upsideDownPhoto() throws Exception {
        readPhoto(2);
    }

    @Test
    @DisplayName("a Russian medical report is read with the Slavic models, recognised as Russian, and waits for a person without any German type")
    void russianPhotoIsNotSortedByGermanRules() throws Exception {
        TesseractOcrEngine engine = new TesseractOcrEngine("tesseract", Duration.ofSeconds(20));
        assumeTrue(engine.availableLanguages().contains("rus"), "The Russian model of Tesseract is not installed here");
        String email = "photo-ru-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"P\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andReturn();
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andReturn().getResponse().getCookie("bc_session");
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "IMG_5120.jpg", "image/jpeg", photo(RUSSIAN, 0))).with(csrf()).cookie(cookie))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(JsonPath.read(upload.getResponse().getContentAsString(), "$.document.id"));
        UUID tenant = jdbc.queryForObject("select tenant_id from documents where id = ?", UUID.class, id);
        RequestContext.runAs(tenant, () -> pipeline.advance(id));

        java.util.Map<String, Object> row = RequestContext.runAs(tenant, () -> jdbc.queryForMap(
                "select coalesce(extracted_text, '') as text, language, document_type_id, processing_status from documents where id = ?", id));
        assertThat((String) row.get("text")).as("the Cyrillic text read from the photo").containsIgnoringCase("комисси");
        assertThat(row.get("language")).isEqualTo("ru");
        assertThat(row.get("document_type_id")).as("no German type for a Russian document").isNull();
        assertThat(row.get("processing_status")).isEqualTo("REVIEW_REQUIRED");
    }
}

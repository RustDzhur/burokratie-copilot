package de.buerokratiecopilot.ai.guide;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductGuideServiceTest {

    @Test
    @DisplayName("a screen the knowledge names is offered, and the marker never reaches the person")
    void offersAKnownScreen() {
        ProductGuideService.Answer answer = ProductGuideService.parse(
                "Öffnen Sie die Integrationen und wählen Sie Postfach verbinden. [[open:/integrations]]");
        assertThat(answer.open()).isEqualTo("/integrations");
        assertThat(answer.text()).doesNotContain("[[").endsWith("verbinden.");
        assertThat(answer.handoff()).isFalse();
    }

    @Test
    @DisplayName("a path that is not a screen of the application is dropped, so the model cannot send anyone elsewhere")
    void dropsUnknownPaths() {
        assertThat(ProductGuideService.parse("Hier. [[open:/evil]]").open()).isNull();
        assertThat(ProductGuideService.parse("Hier. [[open://evil.example/x]]").open()).isNull();
        assertThat(ProductGuideService.parse("Hier. [[open:/admin]]").open())
                .as("the admin area is never offered by the assistant").isNull();
    }

    @Test
    @DisplayName("the handoff marker is recognised and removed")
    void handoff() {
        ProductGuideService.Answer answer = ProductGuideService.parse(
                "[[HANDOFF]] Das klärt der Betreiber mit Ihnen persönlich.");
        assertThat(answer.handoff()).isTrue();
        assertThat(answer.text()).isEqualTo("Das klärt der Betreiber mit Ihnen persönlich.");
    }

    @Test
    @DisplayName("headings and emphasis are stripped because the chat shows plain text")
    void plainText() {
        assertThat(ProductGuideService.parse("## Antwort\n**Wichtig:** zuerst hochladen.").text())
                .isEqualTo("Antwort\nWichtig: zuerst hochladen.");
    }

    @Test
    @DisplayName("every screen the assistant may offer is described in the knowledge, and the knowledge offers no other")
    void knowledgeAndRoutesAgree() throws IOException {
        String knowledge;
        try (var in = ProductGuideServiceTest.class.getResourceAsStream("/ai/product-knowledge.md")) {
            knowledge = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher matcher = Pattern.compile("`(/[a-z-]+)`").matcher(knowledge);
        var described = new java.util.HashSet<String>();
        while (matcher.find()) {
            described.add(matcher.group(1));
        }
        assertThat(described).containsAll(ProductGuideService.routes());
        assertThat(ProductGuideService.routes()).containsAll(described);
    }
}

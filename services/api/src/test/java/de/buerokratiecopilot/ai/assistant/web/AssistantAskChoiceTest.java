package de.buerokratiecopilot.ai.assistant.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The model's answer to a plain-word question: JSON with a tool, JSON with a sentence, or loose prose. */
class AssistantAskChoiceTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("a JSON tool choice is read, fences and prose around it do not matter")
    void readsToolChoice() {
        AssistantToolController.ToolChoice choice = AssistantToolController.parseChoice(json,
                "```json\n{\"tool\":\"create_task\",\"parameters\":{\"title\":\"Prüfen\"}}\n```");

        assertThat(choice.tool()).isEqualTo("create_task");
        assertThat(choice.parameters()).containsEntry("title", "Prüfen");
    }

    @Test
    @DisplayName("a plain answer stays the answer")
    void readsAnswer() {
        AssistantToolController.ToolChoice choice = AssistantToolController.parseChoice(json,
                "{\"answer\":\"Das finden Sie unter /tasks\"}");

        assertThat(choice.tool()).isNull();
        assertThat(choice.answer()).contains("/tasks");
    }

    @Test
    @DisplayName("unparseable output is shown as text rather than dropped")
    void keepsUnparseableText() {
        AssistantToolController.ToolChoice choice = AssistantToolController.parseChoice(json,
                "Ich helfe gerne weiter.");

        assertThat(choice.tool()).isNull();
        assertThat(choice.answer()).contains("helfe");
    }
}

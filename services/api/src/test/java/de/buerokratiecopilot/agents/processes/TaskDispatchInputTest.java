package de.buerokratiecopilot.agents.processes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.tasks.domain.Task;

/**
 * The input rules of {@code task-dispatch}: priority, due date, source, tags and the auto-assign
 * switch are parsed strictly, so a malformed event cannot create a task with a guessed meaning.
 */
class TaskDispatchInputTest {

    @Test
    @DisplayName("the agent id matches the manifest id")
    void idMatchesManifest() {
        assertThat(new TaskDispatchAgent(null, null, null).id()).isEqualTo("task-dispatch");
    }

    @Test
    @DisplayName("priority is one of the five values, absent means the caller's default")
    void parsesPriority() {
        assertThat(TaskDispatchInput.parsePriority(null)).isNull();
        assertThat(TaskDispatchInput.parsePriority("critical")).isEqualTo(Task.Priority.CRITICAL);
        assertThat(TaskDispatchInput.parsePriority("INFORMATIONAL"))
                .isEqualTo(Task.Priority.INFORMATIONAL);
        assertThatThrownBy(() -> TaskDispatchInput.parsePriority("urgent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("dueAt accepts an instant and a bare date, rejects prose")
    void parsesDueAt() {
        assertThat(TaskDispatchInput.parseDueAt(null)).isNull();
        assertThat(TaskDispatchInput.parseDueAt("2026-11-01T09:00:00Z"))
                .isEqualTo(Instant.parse("2026-11-01T09:00:00Z"));
        // A bare date becomes the end of that day in the Berlin zone.
        assertThat(TaskDispatchInput.parseDueAt("2026-11-01"))
                .isEqualTo(Instant.parse("2026-11-01T22:59:59Z"));
        assertThatThrownBy(() -> TaskDispatchInput.parseDueAt("bald"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("source defaults to SYSTEM, unknown sources are refused")
    void parsesSource() {
        assertThat(TaskDispatchInput.parseSource(null, Task.Source.SYSTEM))
                .isEqualTo(Task.Source.SYSTEM);
        assertThat(TaskDispatchInput.parseSource("document", Task.Source.SYSTEM))
                .isEqualTo(Task.Source.DOCUMENT);
        assertThatThrownBy(() -> TaskDispatchInput.parseSource("telegram", Task.Source.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tags are split, trimmed and deduplicated")
    void parsesTags() {
        assertThat(TaskDispatchInput.parseTags(null)).isEmpty();
        assertThat(TaskDispatchInput.parseTags("frist, wichtig ,frist, , "))
                .containsExactly("frist", "wichtig");
    }

    @Test
    @DisplayName("autoAssign is on unless the input says false")
    void parsesAutoAssign() {
        assertThat(TaskDispatchInput.parseAutoAssign(null, true)).isTrue();
        assertThat(TaskDispatchInput.parseAutoAssign("false", true)).isFalse();
        assertThat(TaskDispatchInput.parseAutoAssign("TRUE", false)).isTrue();
        assertThatThrownBy(() -> TaskDispatchInput.parseAutoAssign("ja", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

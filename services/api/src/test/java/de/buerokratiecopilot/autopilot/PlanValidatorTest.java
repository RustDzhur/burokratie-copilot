package de.buerokratiecopilot.autopilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.autopilot.PlanValidator.StepSpec;

/** CA-03: what a plan may be. Every rule has a plan that satisfies it and one that breaks it. */
class PlanValidatorTest {

    private static StepSpec step(String key, String action, String... dependsOn) {
        return new StepSpec(key, action, "Schritt " + key, List.of(dependsOn), false, Map.of());
    }

    private static List<String> codes(List<StepSpec> steps) {
        return PlanValidator.validate(steps).problems().stream().map(PlanValidator.Problem::code).toList();
    }

    private static List<StepSpec> fullLetterPlan() {
        return List.of(step("analyse", "ANALYZE_DOCUMENTS"), step("entwurf", "DRAFT_LETTER", "analyse"),
                step("freigabe", "REQUEST_APPROVAL", "entwurf"), step("senden", "SEND_LETTER", "freigabe"),
                step("warten", "WAIT_FOR_REPLY", "senden"));
    }

    @Test
    @DisplayName("a plan that analyses, drafts, asks for approval, sends and waits is valid")
    void validLetterPlan() {
        assertThat(PlanValidator.validate(fullLetterPlan()).isValid()).isTrue();
    }

    @Test
    @DisplayName("a letter is never sent without an approval that follows its draft")
    void sendNeedsApprovalOfTheDraft() {
        assertThat(codes(List.of(step("entwurf", "DRAFT_LETTER"), step("senden", "SEND_LETTER", "entwurf"))))
                .containsExactly("NO_APPROVAL_BEFORE_ACTION");
        assertThat(codes(List.of(step("senden", "SEND_LETTER")))).containsExactly("NO_APPROVAL_BEFORE_ACTION");
        // An approval of a payment does not release a letter.
        assertThat(codes(List.of(step("zahlung", "PREPARE_PAYMENT"), step("freigabe", "REQUEST_APPROVAL", "zahlung"),
                step("entwurf", "DRAFT_LETTER"), step("senden", "SEND_LETTER", "freigabe", "entwurf"))))
                .containsExactly("NO_APPROVAL_BEFORE_ACTION");
    }

    @Test
    @DisplayName("a payment is never executed without an approval that follows its preparation")
    void paymentNeedsApproval() {
        assertThat(PlanValidator.validate(List.of(step("zahlung", "PREPARE_PAYMENT"), step("freigabe", "REQUEST_APPROVAL", "zahlung"),
                step("ausfuehren", "EXECUTE_PAYMENT", "freigabe"))).isValid()).isTrue();
        assertThat(codes(List.of(step("zahlung", "PREPARE_PAYMENT"), step("ausfuehren", "EXECUTE_PAYMENT", "zahlung"))))
                .containsExactly("NO_APPROVAL_BEFORE_ACTION");
    }

    @Test
    @DisplayName("an approval needs something to decide on, and waiting for a reply needs a sent letter")
    void preconditions() {
        assertThat(codes(List.of(step("freigabe", "REQUEST_APPROVAL")))).containsExactly("MISSING_PRECONDITION");
        assertThat(codes(List.of(step("warten", "WAIT_FOR_REPLY")))).containsExactly("MISSING_PRECONDITION");
    }

    @Test
    @DisplayName("only actions from the closed set are allowed")
    void unknownAction() {
        assertThat(codes(List.of(step("a", "DELETE_EVERYTHING")))).containsExactly("UNKNOWN_ACTION");
        assertThat(codes(List.of(step("a", "SendMessageDirectly")))).containsExactly("UNKNOWN_ACTION");
    }

    @Test
    @DisplayName("dependencies must exist, must not point at the step itself and must not form a loop")
    void dependencies() {
        assertThat(codes(List.of(step("a", "ANALYZE_DOCUMENTS", "ghost")))).containsExactly("UNKNOWN_DEPENDENCY");
        assertThat(codes(List.of(step("a", "ANALYZE_DOCUMENTS", "a")))).containsExactly("SELF_DEPENDENCY");
        assertThat(codes(List.of(step("a", "ANALYZE_DOCUMENTS", "b"), step("b", "COMPARE_WITH_PREVIOUS", "a")))).containsExactly("CYCLE");
    }

    @Test
    @DisplayName("keys are unique and well formed, a plan is not empty and not too long")
    void shape() {
        assertThat(codes(List.of())).containsExactly("EMPTY_PLAN");
        assertThat(codes(List.of(step("a", "ANALYZE_DOCUMENTS"), step("a", "COMPARE_WITH_PREVIOUS")))).contains("DUPLICATE_KEY");
        assertThat(codes(List.of(step("Bad Key!", "ANALYZE_DOCUMENTS")))).contains("BAD_KEY");
        List<StepSpec> many = new ArrayList<>();
        for (int i = 0; i < PlanValidator.MAX_STEPS + 1; i++) {
            many.add(step("s" + i, "ANALYZE_DOCUMENTS"));
        }
        assertThat(codes(many)).contains("TOO_MANY_STEPS");
    }

    @Test
    @DisplayName("a plan carries no letter text, address or account number: only the parameters of the action are allowed")
    void parameters() {
        assertThat(codes(List.of(new StepSpec("entwurf", "DRAFT_LETTER", "Entwurf", List.of(), false,
                Map.of("body", "Sehr geehrte Damen und Herren, hiermit kündige ich")))))
                .containsExactly("PARAM_NOT_ALLOWED");
        assertThat(codes(List.of(new StepSpec("zahlung", "PREPARE_PAYMENT", "Zahlung", List.of(), false, Map.of("iban", "DE89370400440532013000")))))
                .containsExactly("PARAM_NOT_ALLOWED");
        assertThat(PlanValidator.validate(List.of(new StepSpec("aufgabe", "CREATE_TASK", "Aufgabe", List.of(), false,
                Map.of("taskTitle", "Vertrag prüfen", "dueInDays", 7)))).isValid()).isTrue();
    }

    @Test
    @DisplayName("every step needs a title")
    void titles() {
        assertThat(codes(List.of(new StepSpec("a", "ANALYZE_DOCUMENTS", " ", List.of(), false, Map.of())))).containsExactly("BAD_TITLE");
    }
}

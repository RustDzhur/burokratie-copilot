package de.buerokratiecopilot.agents.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.hr.OnboardingAgent.PlanTask;

/** Verifies the deterministic onboarding checklist of {@code hr-onboarding}. */
class OnboardingAgentTest {

    @Test
    void thePlanCoversTheFirstDaysAndTheProbationReview() {
        List<PlanTask> plan = OnboardingAgent.plan("Erika Muster", "Buchhaltung", "Finanzen");

        assertThat(plan).hasSize(6);
        assertThat(plan).extracting(PlanTask::title)
                .allMatch(title -> title.startsWith("Onboarding Erika Muster: "));
        assertThat(plan).extracting(PlanTask::daysOffset)
                .containsExactly(-5, -3, -2, 0, 0, 90);
        assertThat(plan.get(5).title()).contains("Probezeitgespräch");
    }

    @Test
    void everyTaskHasACompletionCriterion() {
        List<PlanTask> plan = OnboardingAgent.plan("Max", "Montage", "Werkstatt");

        assertThat(plan).allSatisfy(task -> {
            assertThat(task.description()).isNotBlank();
            assertThat(task.completionCriteria()).isNotBlank();
        });
    }

    @Test
    void theRoleAndDepartmentReachTheItAndContractTasks() {
        List<PlanTask> plan = OnboardingAgent.plan("Ana", "Vertrieb", "Verkauf");

        assertThat(plan.get(0).description()).contains("Verkauf");
        assertThat(plan.get(1).description()).contains("Vertrieb");
    }
}

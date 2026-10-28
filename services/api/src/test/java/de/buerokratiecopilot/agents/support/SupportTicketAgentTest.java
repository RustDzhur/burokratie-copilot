package de.buerokratiecopilot.agents.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.support.SupportTicketAgent.Topic;
import de.buerokratiecopilot.agents.support.SupportTicketAgent.Triage;
import de.buerokratiecopilot.tasks.domain.Task;

/** Verifies the deterministic triage of {@code support-ticket}. */
class SupportTicketAgentTest {

    @Test
    void aCriticalWordEscalatesAndSetsTheOneHourSla() {
        Triage triage = SupportTicketAgent.triage("Kompletter Ausfall", "Server nicht erreichbar",
                null);

        assertThat(triage.priority()).isEqualTo(Task.Priority.CRITICAL);
        assertThat(triage.topic()).isEqualTo(Topic.TECHNIK);
        assertThat(triage.slaMinutes()).isEqualTo(60);
        assertThat(triage.rationale()).contains("Schlüsselwort");
    }

    @Test
    void anInvoiceProblemIsHighAndTopicInvoice() {
        Triage triage = SupportTicketAgent.triage("Rechnung falsch",
                "Die Rechnung ist doppelt und die Mahnung kommt", null);

        assertThat(triage.priority()).isEqualTo(Task.Priority.HIGH);
        assertThat(triage.topic()).isEqualTo(Topic.RECHNUNG);
        assertThat(triage.slaMinutes()).isEqualTo(240);
    }

    @Test
    void aGeneralQuestionIsLowButNotUrgent() {
        Triage triage = SupportTicketAgent.triage("Allgemeine Frage",
                "Ich hätte eine Frage zum Angebot", null);

        assertThat(triage.priority()).isEqualTo(Task.Priority.LOW);
        assertThat(triage.topic()).isEqualTo(Topic.ALLGEMEIN);
        assertThat(triage.slaMinutes()).isEqualTo(4320);
    }

    @Test
    void theReporterHintWinsOverTheKeywords() {
        Triage triage = SupportTicketAgent.triage("Frage", "Kleine Frage", "critical");

        assertThat(triage.priority()).isEqualTo(Task.Priority.CRITICAL);
        assertThat(triage.rationale()).contains("Melder");
    }

    @Test
    void anInvalidHintIsIgnored() {
        Triage triage = SupportTicketAgent.triage("Neue Anfrage", "Bitte um Rückruf", "sofort!!");

        assertThat(triage.priority()).isEqualTo(Task.Priority.NORMAL);
    }

    @Test
    void everyPriorityHasAnIncreasingSla() {
        assertThat(SupportTicketAgent.slaMinutes(Task.Priority.CRITICAL)).isLessThan(
                SupportTicketAgent.slaMinutes(Task.Priority.HIGH));
        assertThat(SupportTicketAgent.slaMinutes(Task.Priority.HIGH)).isLessThan(
                SupportTicketAgent.slaMinutes(Task.Priority.NORMAL));
        assertThat(SupportTicketAgent.slaMinutes(Task.Priority.NORMAL)).isLessThan(
                SupportTicketAgent.slaMinutes(Task.Priority.LOW));
    }
}

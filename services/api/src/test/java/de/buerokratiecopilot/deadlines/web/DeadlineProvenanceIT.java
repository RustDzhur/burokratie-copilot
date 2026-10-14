package de.buerokratiecopilot.deadlines.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.deadlines.TaskDeadlineRepository;
import de.buerokratiecopilot.deadlines.domain.TaskDeadline;
import de.buerokratiecopilot.deadlines.web.DeadlineController.DeadlineResponse;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.identity.onboarding.OnboardingService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.outbox.OutboxDispatcher;
import de.buerokratiecopilot.support.TestDocuments;

/**
 * RD-05: a date read from a document says where it was read (document, page, text) and stays "unconfirmed" until a
 * person confirms it; a date counted from a rule says which version of the rule counted it.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeadlineProvenanceIT {

    private static final DateTimeFormatter GERMAN = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Autowired
    private OnboardingService onboarding;
    @Autowired
    private DocumentService documents;
    @Autowired
    private OutboxDispatcher outbox;
    @Autowired
    private TaskDeadlineRepository deadlines;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a stated deadline carries its document, page and text, is never legally confirmed, and is reported as unconfirmed")
    void statedDeadlineIsTraceableAndUnconfirmed() throws Exception {
        var created = onboarding.onboard(new OnboardingService.Request(
                "prov-" + UUID.randomUUID().toString().substring(0, 12), "Herkunft", "PRIVATE_PERSON",
                "prov-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de", "Erika Mustermann",
                "de", "Europe/Berlin", "DE", "Herkunft"));
        UUID tenantId = created.tenantId();
        LocalDate due = LocalDate.now().plusDays(12);
        String text = """
                Stadtwerke Musterstadt GmbH
                Letzte Mahnung
                Rechnungsnummer: RE-2026-%d
                Offener Betrag: 280,00 EUR
                Mahngebühr: 5,00 EUR
                Frist: %s
                Bitte zahlen Sie den Betrag. Andernfalls schalten wir ein Inkassounternehmen ein.
                """.formatted(System.nanoTime() % 100000, due.format(GERMAN));
        byte[] pdf = TestDocuments.pdf(text);
        UUID documentId = RequestContext.runAs(tenantId, () -> documents.receive(
                new DocumentService.UploadRequest(new MockMultipartFile("file", "mahnung.pdf", "application/pdf",
                        pdf), null, null, Document.Source.UPLOAD, "Stadtwerke Musterstadt GmbH"))
                .document().getId());
        for (int round = 0; round < 4; round++) {
            outbox.dispatch();
        }

        List<TaskDeadline> stated = RequestContext.runAs(tenantId, () -> deadlines.findAll().stream()
                .filter(d -> documentId.equals(d.getSourceDocumentId()) && d.getSource() == TaskDeadline.Source.DOCUMENT)
                .toList());
        assertThat(stated).hasSize(1);
        TaskDeadline deadline = stated.get(0);
        assertThat(deadline.getDueDate()).isEqualTo(due);
        assertThat(deadline.getSourcePage()).as("the page the date was read from").isEqualTo(1);
        assertThat(deadline.getSourceSnippet()).contains(due.format(GERMAN));
        assertThat(deadline.getDeadlineType()).as("a date read from a letter is not a confirmed legal deadline")
                .isNotEqualTo(TaskDeadline.DeadlineType.CONFIRMED_LEGAL);
        assertThat(deadline.isRequiresVerification()).isTrue();

        DeadlineResponse response = DeadlineResponse.from(deadline);
        assertThat(response.confirmation()).isEqualTo("UNCONFIRMED");
        assertThat(response.sourcePage()).isEqualTo(1);
        assertThat(response.sourceDocumentId()).isEqualTo(documentId.toString());
        assertThat(response.sourceSnippet()).contains(due.format(GERMAN));

        // After a person confirms it, the response says so.
        RequestContext.runAs(tenantId, () -> {
            TaskDeadline stored = deadlines.findById(deadline.getId()).orElseThrow();
            stored.confirm(created.adminUserId(), "geprüft");
            deadlines.save(stored);
            return null;
        });
        assertThat(DeadlineResponse.from(RequestContext.runAs(tenantId,
                () -> deadlines.findById(deadline.getId()).orElseThrow())).confirmation()).isEqualTo("CONFIRMED");
    }
}

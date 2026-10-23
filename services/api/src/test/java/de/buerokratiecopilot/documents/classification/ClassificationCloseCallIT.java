package de.buerokratiecopilot.documents.classification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.deadlines.TaskDeadlineRepository;
import de.buerokratiecopilot.deadlines.domain.TaskDeadline;
import de.buerokratiecopilot.documents.DocumentClassificationRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentReviewRepository;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentReview;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.documents.review.ReviewService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.outbox.OutboxDispatcher;
import de.buerokratiecopilot.support.TestAccounts;
import de.buerokratiecopilot.support.TestDocuments;

/**
 * A close call between two types of <em>one</em> category (spec §9.3).
 *
 * <p>The rule this class protects: a second type of the same area is a reason to ask a person only when the two
 * types would read the deadline from different fields. "Mahnung" and "Zahlungserinnerung" are both finance but read
 * the date from different fields ({@code Frist} against {@code Frist} and {@code Zahlungsziel}), so a letter that
 * could be either must not be acted upon automatically. "Mahnung" and "Inkasso" read it from the same field, so the
 * date on the paper lands in the same place either way and the document is filed automatically.</p>
 *
 * <p>The second half of the test is what makes the first half safe: while the type waits for a person nothing is
 * recorded as a deadline, and once that person confirms the suggestion, the date from the document becomes a
 * deadline that is traceable to its page and text and is explicitly <strong>unconfirmed</strong> — the platform
 * never calls a date it read a confirmed one.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ClassificationCloseCallIT {

    private static final DateTimeFormatter GERMAN = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private DocumentService documents;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentClassificationRepository classifications;

    @Autowired
    private DocumentReviewRepository reviews;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    @Autowired
    private TaskDeadlineRepository deadlines;

    @Autowired
    private OutboxDispatcher outbox;

    private record Account(UUID tenantId, UUID adminUserId) {
    }

    private Account account() {
        var created = accounts.create("close");
        return new Account(created.tenantId(), created.adminUserId());
    }

    private UUID upload(Account account, String filename, String text) throws java.io.IOException {
        byte[] pdf = TestDocuments.pdf(text);
        return RequestContext.runAs(account.tenantId(), () -> documents.receive(
                new DocumentService.UploadRequest(new MockMultipartFile("file", filename, "application/pdf",
                        pdf), null, null, Document.Source.UPLOAD,
                        "Stadtwerke Musterstadt GmbH")).document().getId());
    }

    private void advance(Account account, UUID documentId) {
        RequestContext.runAs(account.tenantId(), () -> pipeline.advance(documentId));
    }

    private void runOutbox() {
        for (int round = 0; round < 4; round++) {
            outbox.dispatch();
        }
    }

    private Document document(Account account, UUID documentId) {
        return RequestContext.runAs(account.tenantId(),
                () -> documentRepository.findById(documentId).orElseThrow());
    }

    private DocumentReview openReview(Account account, UUID documentId) {
        return RequestContext.runAs(account.tenantId(), () -> reviews
                .findByDocumentIdOrderByCreatedAtDesc(documentId).stream()
                .filter(DocumentReview::isOpen)
                .findFirst().orElseThrow());
    }

    private List<TaskDeadline> documentDeadlines(Account account, UUID documentId) {
        return RequestContext.runAs(account.tenantId(), () -> deadlines.findAll().stream()
                .filter(deadline -> documentId.equals(deadline.getSourceDocumentId()))
                .filter(deadline -> deadline.getSource() == TaskDeadline.Source.DOCUMENT)
                .toList());
    }

    @Test
    @DisplayName("two finance types that read the deadline from different fields wait for a person, and the stated date becomes an unconfirmed deadline once that person confirms")
    void closeCallWithinOneCategoryWaitsForAPerson() throws Exception {
        Account account = account();
        LocalDate due = LocalDate.now().plusDays(12);
        // A letter that could be a Zahlungserinnerung and a Mahnung at once: both types read the date from
        // different fields, so the two possible answers produce different deadlines.
        UUID documentId = upload(account, "zahlungserinnerung.pdf", """
                Stadtwerke Musterstadt GmbH
                Zahlungserinnerung – zugleich letzte Mahnung vor weiteren Schritten
                Rechnungsnummer: RE-2026-%d
                Offener Betrag: 280,00 EUR
                Mahngebühr: 5,00 EUR
                Frist: %s
                Bitte zahlen Sie den Betrag.
                """.formatted(System.nanoTime() % 100000, due.format(GERMAN)));
        advance(account, documentId);

        Document pending = document(account, documentId);
        assertThat(pending.getProcessingStatus())
                .as("a close call between two types of one category is still an undecided classification")
                .isEqualTo(Document.ProcessingStatus.REVIEW_REQUIRED);
        assertThat(pending.getDocumentTypeId())
                .as("the type waits for a person; acting on the closer guess would be automation on a coin toss")
                .isNull();
        assertThat(pending.getReviewStatus()).isEqualTo(Document.ReviewStatus.PENDING);

        DocumentReview review = openReview(account, documentId);
        assertThat(review.getReviewType()).isEqualTo(DocumentReview.ReviewType.CLASSIFICATION);
        assertThat(review.getReason())
                .as("the queue says which two types are equally plausible and why that matters")
                .contains("Mahnung")
                .contains("Zahlungserinnerung")
                .contains("Frist aus verschiedenen Feldern");
        assertThat(RequestContext.runAs(account.tenantId(),
                () -> classifications.findByDocumentIdAndCurrentTrue(documentId)))
                .as("the guess is stored, so a reviewer sees it and can accept or correct it")
                .isPresent();

        assertThat(documentDeadlines(account, documentId))
                .as("nothing is recorded as a deadline while the type is only guessed")
                .isEmpty();

        // The reviewer confirms the suggested type. From here the document is processed further, and the date
        // stated in it becomes a deadline: read from the document, traceable, and never called confirmed.
        RequestContext.runAs(account.tenantId(), () -> {
            reviewService.decide(review.getId(), DocumentReview.Status.APPROVED, "Zahlungserinnerung bestätigt",
                    null, account.adminUserId());
            pipeline.advance(documentId);
            return null;
        });
        runOutbox();

        assertThat(document(account, documentId).getDocumentTypeId())
                .as("the type the reviewer confirmed is applied")
                .isNotNull();
        List<TaskDeadline> stated = documentDeadlines(account, documentId);
        assertThat(stated).hasSize(1);
        TaskDeadline deadline = stated.get(0);
        assertThat(deadline.getDueDate()).isEqualTo(due);
        assertThat(deadline.getDeadlineType()).isEqualTo(TaskDeadline.DeadlineType.DOCUMENT_DATE);
        assertThat(deadline.getSourcePage()).as("the page the date was read from").isEqualTo(1);
        assertThat(deadline.getSourceSnippet()).contains(due.format(GERMAN));
        assertThat(deadline.getConfirmedAt())
                .as("a date read from a letter is a reading, not a confirmed fact")
                .isNull();
        assertThat(deadline.isRequiresVerification()).isTrue();
    }

    @Test
    @DisplayName("two finance types that read the deadline from the same field are filed automatically, because the date lands in the same place either way")
    void sameDeadlineFieldStaysAutomatic() throws Exception {
        Account account = account();
        LocalDate due = LocalDate.now().plusDays(12);
        // "Letzte Mahnung ... Inkassounternehmen" scores almost as well for Inkasso as for Mahnung, but both read
        // their deadline from the same field (Frist): the document is a dunning letter either way and the date on
        // it is the same date, so asking a person would only delay a deadline that is not in doubt.
        UUID documentId = upload(account, "mahnung.pdf", """
                Stadtwerke Musterstadt GmbH
                Letzte Mahnung
                Rechnungsnummer: RE-2026-%d
                Offener Betrag: 280,00 EUR
                Mahngebühr: 5,00 EUR
                Frist: %s
                Bitte zahlen Sie den Betrag. Andernfalls schalten wir ein Inkassounternehmen ein.
                """.formatted(System.nanoTime() % 100000, due.format(GERMAN)));
        advance(account, documentId);
        runOutbox();

        Document filed = document(account, documentId);
        assertThat(filed.getProcessingStatus()).isEqualTo(Document.ProcessingStatus.READY);
        assertThat(filed.getDocumentTypeId()).isNotNull();
        assertThat(RequestContext.runAs(account.tenantId(), () -> reviews
                .findByDocumentIdOrderByCreatedAtDesc(documentId)))
                .as("no person is asked about a pair that would read the same date")
                .isEmpty();

        List<TaskDeadline> stated = documentDeadlines(account, documentId);
        assertThat(stated).hasSize(1);
        assertThat(stated.get(0).getDueDate()).isEqualTo(due);
        assertThat(stated.get(0).isRequiresVerification()).isTrue();
    }
}

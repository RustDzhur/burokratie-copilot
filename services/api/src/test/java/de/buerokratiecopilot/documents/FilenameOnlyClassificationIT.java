package de.buerokratiecopilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.documents.classification.ClassificationService;
import de.buerokratiecopilot.shared.context.RequestContext;

/** A file name alone never classifies a document: nobody has read what the picture says. */
@SpringBootTest
@ActiveProfiles("test")
class FilenameOnlyClassificationIT {

    @Autowired
    private ClassificationService classification;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a photo called 'BWA 2025.jpg' with no text read from it is sent to a person, not filed as a report")
    void noTextMeansNoClassification() {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("insert into tenants (id, slug, name, account_type) values (?, ?, 'T', 'COMPANY')", tenantId,
                "t-" + tenantId.toString().substring(0, 8));
        UUID documentId = RequestContext.runAs(tenantId, () -> jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type, size_bytes, content_hash,
                                       source, processing_status, review_status)
                values (gen_random_uuid(), ?, 'BWA 2025.jpg', 'x.jpg', 'image/jpeg', 100, ?, 'UPLOAD', 'EXTRACTING', 'NOT_REQUIRED')
                returning id
                """, UUID.class, tenantId, "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong())));

        var outcome = RequestContext.runAs(tenantId, () -> classification.classify(
                documents.findById(documentId).orElseThrow(), null, null));

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.typeCode()).isNull();
        assertThat(outcome.requiresReview()).isTrue();
        assertThat(outcome.rationale()).contains("kein Text");
    }
}

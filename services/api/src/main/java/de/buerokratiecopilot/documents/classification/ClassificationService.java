package de.buerokratiecopilot.documents.classification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.documents.DocumentClassificationRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentTypeRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentClassification;
import de.buerokratiecopilot.documents.domain.DocumentType;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * Classifies a document and decides what the confidence means (spec §9.3, §9.4, §32).
 *
 * <p>The three-way decision is the heart of this class:</p>
 * <ul>
 *   <li><strong>at or above the acceptance threshold</strong> — the type is applied and processing
 *       continues automatically;</li>
 *   <li><strong>between the thresholds</strong> — a plausible guess is stored, but a human confirms it
 *       before anything acts on it. Silently accepting a 0.7 guess would mean automating on a coin
 *       flip;</li>
 *   <li><strong>below the review threshold or no match at all</strong> — the document goes to review
 *       with the reason, and the type stays unset rather than being filled with the closest guess.</li>
 * </ul>
 *
 * <p>Both thresholds are configurable per tenant (spec §31) and default to the values seeded with the
 * reference data. A type marked {@code requires_review} overrides the confidence: legal and financial
 * document types of the taxonomy must always be seen by a person, however confident the classifier
 * is — the specification forbids treating a model's output as legally reliable on its own.</p>
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    /** Seeded defaults; a tenant can override both through the settings. */
    private static final double DEFAULT_ACCEPT_THRESHOLD = 0.85;
    private static final double DEFAULT_REVIEW_THRESHOLD = 0.60;

    private final DocumentClassifier classifier;
    private final DocumentTypeRepository types;
    private final DocumentClassificationRepository classifications;
    private final DocumentRepository documents;
    private final SettingsService settings;
    private final StatedDeadlineFields statedDeadline;

    public ClassificationService(DocumentClassifier classifier, DocumentTypeRepository types,
                                 DocumentClassificationRepository classifications,
                                 DocumentRepository documents, SettingsService settings,
                                 StatedDeadlineFields statedDeadline) {
        this.classifier = classifier;
        this.types = types;
        this.classifications = classifications;
        this.documents = documents;
        this.settings = settings;
        this.statedDeadline = statedDeadline;
    }

    /** What the classification stage decided, and why. */
    public record Outcome(boolean accepted, UUID typeId, String typeCode, Double confidence,
                          String rationale, boolean requiresReview) {

        public Map<String, Object> diagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("method", "RULE");
            details.put("accepted", accepted);
            details.put("typeCode", typeCode == null ? "" : typeCode);
            details.put("confidence", confidence);
            details.put("requiresReview", requiresReview);
            details.put("rationale", rationale);
            return details;
        }
    }

    @Transactional
    public Outcome classify(Document document, DocumentVersion version, String senderEmail) {
        double acceptThreshold = settings.getDouble("classification.auto_accept_threshold",
                DEFAULT_ACCEPT_THRESHOLD);
        double reviewThreshold = settings.getDouble("classification.review_threshold",
                DEFAULT_REVIEW_THRESHOLD);

        // The platform reads German documents. A document in another script cannot be sorted by German rules; a guess from
        // the few Latin look-alikes in it would file it under a wrong drawer, so it goes to a person with the reason.
        if ("ru".equals(document.getLanguage())) {
            return new Outcome(false, null, null, null,
                    "Das Dokument ist nicht deutschsprachig (erkannte Sprache: Russisch); es wird nicht nach deutschen Regeln einsortiert", true);
        }

        // A file name is a hint, not a reading. Without any text read from the content, a name like "BWA 2025.jpg" would be
        // filed as a business report and a task created for it, although nobody has seen what the picture says.
        if (document.getExtractedText() == null || document.getExtractedText().isBlank()) {
            return new Outcome(false, null, null, null,
                    "Aus dem Dokument konnte kein Text gelesen werden; der Dateiname allein reicht für eine Einordnung nicht", true);
        }

        DocumentClassifier.Classification result = classifier.classify(new DocumentClassifier.Input(
                document.getTenantId(), textFor(document, senderEmail), document.getOriginalFilename(),
                senderEmail, document.getSender()));

        if (result.isEmpty()) {
            // Nothing matched: the type stays unset and the reason is recorded. Filling in the
            // closest type would produce a wrong classification that looks confident.
            return new Outcome(false, null, null, null, result.rationale(), true);
        }

        DocumentType type = types.findById(result.typeId()).orElse(null);
        if (type == null || !type.isActive()) {
            return new Outcome(false, null, null, null,
                    "The recognised document type is no longer available", true);
        }

        double confidence = result.confidence() == null ? 0 : result.confidence();
        boolean requiresReview = type.isRequiresReview() || confidence < acceptThreshold;
        String closeCall = closeCall(type, confidence, result.alternative(), document.getTenantId());
        boolean singleFeature = result.matchedRules() != null && result.matchedRules().size() < 2;
        if (closeCall != null || singleFeature) {
            requiresReview = true;
        }

        store(document, version, type, result, confidence);

        if (requiresReview) {
            String reason = closeCall != null ? closeCall
                    : singleFeature && !type.isRequiresReview() && confidence >= acceptThreshold
                    ? "Nur ein Merkmal spricht für '%s'; ein einzelnes Wort reicht nicht für eine automatische Ablage".formatted(type.nameIn("de"))
                    : type.isRequiresReview()
                    ? "Document type '%s' always requires human confirmation".formatted(type.nameIn("de"))
                    : "Confidence %.2f is below the acceptance threshold %.2f".formatted(confidence,
                            acceptThreshold);
            log.info("Document {} classified as {} with confidence {} but sent to review: {}",
                    document.getId(), type.getCode(), confidence, reason);
            return new Outcome(false, type.getId(), type.getCode(), confidence,
                    result.rationale() + " — " + reason, true);
        }

        document.setDocumentTypeId(type.getId());
        documents.save(document);
        log.info("Document {} classified as {} with confidence {}", document.getId(), type.getCode(),
                confidence);
        return new Outcome(true, type.getId(), type.getCode(), confidence, result.rationale(), false);
    }

    /** How close the second type may be before two different areas count as undecided. */
    private static final double CLOSE_CALL_MARGIN = 0.08;
    private static final double CLOSE_CALL_FLOOR = 0.5;

    /**
     * A reason to ask a person when the best and the second-best type are nearly equal: the higher number is then luck,
     * not evidence, and an automatic filing would be a coin toss.
     *
     * <p>A second type of the same area used to be accepted without asking, on the ground that the drawer the document
     * lands in does not change. That was wrong wherever the work changes: "Mahnung" and "Zahlungserinnerung" both belong
     * to finance, but they read the stated deadline from different fields (the Mahnung a {@code Frist}, the
     * Zahlungserinnerung a {@code Frist} and a {@code Zahlungsziel}), so the same letter would produce a different date
     * depending on which of the two was applied (spec §9.3). Such a pair goes to a person like any other close call. A
     * pair of one area that reads the deadline from the same fields stays automatic: the date on the paper lands in the
     * same place either way, which is the promise the product makes for a letter with a date on it.</p>
     */
    private String closeCall(DocumentType best, double confidence,
                             DocumentClassifier.Classification.Alternative alternative, UUID tenantId) {
        if (alternative == null || alternative.score() < CLOSE_CALL_FLOOR || confidence - alternative.score() >= CLOSE_CALL_MARGIN) {
            return null;
        }
        DocumentType other = types.findById(alternative.typeId()).orElse(null);
        if (other == null) {
            return null;
        }
        boolean sameArea = other.getCategory().equals(best.getCategory());
        if (sameArea && !readsDeadlineFromDifferentFields(best, other, tenantId)) {
            return null;
        }
        return (sameArea
                ? "Zwei Typen aus demselben Bereich lesen die Frist aus verschiedenen Feldern: '%s' und '%s'"
                : "Zwei Typen aus verschiedenen Bereichen passen fast gleich gut: '%s' und '%s'")
                .formatted(best.nameIn("de"), other.nameIn("de"));
    }

    /**
     * Whether the two types would read the deadline the document states from different fields.
     *
     * <p>Conservative where the configuration is silent: a type without a scenario or without due fields counts as
     * "different", because nothing then shows that the date would land in the same place.</p>
     */
    private boolean readsDeadlineFromDifferentFields(DocumentType best, DocumentType other, UUID tenantId) {
        return !statedDeadline.of(tenantId, best.getCode()).equals(statedDeadline.of(tenantId, other.getCode()));
    }

    /** A manual correction: stores the decision as MANUAL and makes it the current one. */
    @Transactional
    public DocumentClassification correctManually(Document document, DocumentVersion version,
                                                   DocumentType type, UUID userId, String note) {
        DocumentClassifier.Classification manual = new DocumentClassifier.Classification(type.getId(),
                type.getCode(), 1.0, java.util.List.of("manual correction"), note);
        store(document, version, type, manual, 1.0);
        document.setDocumentTypeId(type.getId());
        documents.save(document);
        return classifications.findByDocumentIdAndCurrentTrue(document.getId()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public java.util.List<DocumentClassification> historyOf(UUID documentId) {
        return classifications.findByDocumentIdOrderByCreatedAtDesc(documentId);
    }

    private void store(Document document, DocumentVersion version, DocumentType type,
                       DocumentClassifier.Classification result, double confidence) {
        classifications.clearCurrent(document.getId());
        DocumentClassification entity = new DocumentClassification(UUID.randomUUID(), document.getId(),
                version == null ? null : version.getId(), type.getId(), classifier.method());
        entity.setConfidence(BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP));
        entity.setRationale(result.rationale());
        entity.setCurrent(true);
        classifications.save(entity);
    }

    /** Text used by the classifier: extracted text plus file name, which is often telling. */
    private static String textFor(Document document, String senderEmail) {
        StringBuilder builder = new StringBuilder();
        if (document.getExtractedText() != null) {
            builder.append(document.getExtractedText());
        }
        if (document.getOriginalFilename() != null) {
            builder.append('\n').append(document.getOriginalFilename());
        }
        if (document.getSender() != null) {
            builder.append('\n').append(document.getSender());
        }
        if (senderEmail != null) {
            builder.append('\n').append(senderEmail);
        }
        return builder.toString();
    }

    /** Below this the classifier's guess is not even stored as a current decision. */
    @Transactional(readOnly = true)
    public double reviewThreshold() {
        return settings.getDouble("classification.review_threshold", DEFAULT_REVIEW_THRESHOLD);
    }
}

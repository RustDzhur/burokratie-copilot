package de.buerokratiecopilot.documents.dates;

import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Classifies a date with the vocabulary that this workspace actually has (spec §9.3).
 *
 * <p>This is the production entry point. {@link DateClassifier} holds the algorithm and a test-only subset
 * of words; the words themselves live in the database since migration V69. Every call therefore needs the
 * workspace: a tenant can have added a word the defaults do not contain, and it can have switched a default
 * off. Reading them through {@link DateMarkerService} is what makes an edit in the settings screen take
 * effect for the next document instead of the next deployment.</p>
 *
 * <p><strong>Why a bean and not a static call.</strong> The service has state (the cached merged vocabulary)
 * and a database connection; neither belongs in a static utility. A bean also makes it visible in tests that
 * the production path and the unit-test path are different on purpose.</p>
 */
@Component
public class DateMarkerClassifier {

    private final DateMarkerService markers;

    public DateMarkerClassifier(DateMarkerService markers) {
        this.markers = markers;
    }

    /**
     * Classifies the date that appears at {@code dateAt} in {@code text}.
     *
     * <p>The workspace comes from the request context, the same way every tenant-scoped read does. Without
     * one (a background thread that has not set the context) only the platform defaults are visible; that
     * still classifies correctly for every account that never changed the vocabulary, and it never sees a
     * foreign workspace's words.</p>
     */
    public DateClassifier.Classified classify(String text, int dateAt, int dateLength) {
        UUID tenantId = RequestContext.tenantId().orElse(null);
        return DateClassifier.classify(text, dateAt, dateLength, markers.active(tenantId));
    }
}

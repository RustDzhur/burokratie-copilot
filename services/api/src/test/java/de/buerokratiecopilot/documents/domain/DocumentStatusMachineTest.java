package de.buerokratiecopilot.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import de.buerokratiecopilot.documents.domain.Document.ProcessingStatus;

import static de.buerokratiecopilot.documents.domain.Document.ProcessingStatus.*;

/**
 * HD-TEST-03: every pair of statuses is checked against the graph written out here, independently of the production table,
 * so that a change to the graph has to be made in two places on purpose.
 */
class DocumentStatusMachineTest {

    /** The transitions the pipeline allows (spec 6.3), written by hand. */
    private static final Map<ProcessingStatus, Set<ProcessingStatus>> EXPECTED = Map.ofEntries(
            Map.entry(RECEIVED, Set.of(QUARANTINED, UNSUPPORTED, DUPLICATE, CANCELLED, FAILED)),
            Map.entry(QUARANTINED, Set.of(OCR_PENDING, EXTRACTING, REVIEW_REQUIRED, FAILED, CANCELLED)),
            Map.entry(OCR_PENDING, Set.of(EXTRACTING, REVIEW_REQUIRED, FAILED)),
            Map.entry(EXTRACTING, Set.of(CLASSIFYING, REVIEW_REQUIRED, FAILED)),
            Map.entry(CLASSIFYING, Set.of(MATCHING, REVIEW_REQUIRED, READY, FAILED)),
            Map.entry(MATCHING, Set.of(READY, REVIEW_REQUIRED, FAILED)),
            Map.entry(REVIEW_REQUIRED, Set.of(READY, MATCHING, CLASSIFYING, EXTRACTING, FAILED, CANCELLED)),
            Map.entry(READY, Set.of(ACTION_PENDING, COMPLETED, REVIEW_REQUIRED, ARCHIVED, LEGAL_HOLD, FAILED)),
            Map.entry(ACTION_PENDING, Set.of(COMPLETED, READY, FAILED)),
            Map.entry(COMPLETED, Set.of(ARCHIVED, LEGAL_HOLD, READY)),
            Map.entry(FAILED, Set.of(RECEIVED, OCR_PENDING, EXTRACTING, CLASSIFYING, CANCELLED)),
            Map.entry(DUPLICATE, Set.of(ARCHIVED, CANCELLED)),
            Map.entry(ARCHIVED, Set.of(LEGAL_HOLD)),
            Map.entry(LEGAL_HOLD, Set.of(ARCHIVED, READY)),
            Map.entry(UNSUPPORTED, Set.of()),
            Map.entry(CANCELLED, Set.of()));

    static Stream<Arguments> everyPair() {
        Stream.Builder<Arguments> pairs = Stream.builder();
        for (ProcessingStatus from : ProcessingStatus.values()) {
            for (ProcessingStatus to : ProcessingStatus.values()) {
                boolean allowed = from == to || EXPECTED.get(from).contains(to);
                pairs.add(Arguments.of(from, to, allowed));
            }
        }
        return pairs.build();
    }

    @Test
    @DisplayName("the hand-written graph covers every status")
    void graphIsComplete() {
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(ProcessingStatus.values());
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @MethodSource("everyPair")
    @DisplayName("a transition is allowed exactly when the pipeline says so")
    void transition(ProcessingStatus from, ProcessingStatus to, boolean allowed) {
        assertThat(DocumentStatusMachine.canTransition(from, to)).isEqualTo(allowed);
        if (allowed) {
            DocumentStatusMachine.requireTransition(from, to);
        }
        else {
            assertThatThrownBy(() -> DocumentStatusMachine.requireTransition(from, to))
                    .isInstanceOf(InvalidDocumentStatusTransition.class)
                    .hasMessageContaining(from.name()).hasMessageContaining(to.name());
        }
    }

    @Test
    @DisplayName("nextStates lists exactly the allowed targets and is a copy")
    void nextStates() {
        for (ProcessingStatus from : ProcessingStatus.values()) {
            assertThat(DocumentStatusMachine.nextStates(from)).containsExactlyInAnyOrderElementsOf(EXPECTED.get(from));
        }
        assertThatThrownBy(() -> DocumentStatusMachine.nextStates(READY).add(RECEIVED)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a finished or cancelled document cannot silently re-enter the pipeline")
    void noWayBackIntoTheStart() {
        Set<ProcessingStatus> entrances = EnumSet.of(RECEIVED, OCR_PENDING, QUARANTINED);
        for (ProcessingStatus dead : new ProcessingStatus[] { CANCELLED, UNSUPPORTED, ARCHIVED, COMPLETED, DUPLICATE }) {
            for (ProcessingStatus entrance : entrances) {
                assertThat(DocumentStatusMachine.canTransition(dead, entrance)).as(dead + " -> " + entrance).isFalse();
            }
        }
    }
}

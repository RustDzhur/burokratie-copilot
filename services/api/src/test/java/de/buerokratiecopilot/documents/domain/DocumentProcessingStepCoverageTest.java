package de.buerokratiecopilot.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;

/**
 * Every stage the specification names is either executed or recorded as not executed (spec §9.1).
 *
 * <p><strong>Why this test exists.</strong> The enum declared fifteen stages, the pipeline ran ten, and
 * nothing compared the two. The five that did not run — contradiction check, action derivation, risk
 * assessment, persist, index — were invisible: a reader of the enum would believe the product does them, and
 * a reader of the pipeline would not know they were ever expected.</p>
 *
 * <p>The test makes the gap a decision instead of an oversight: a new enum value must be added to the
 * execution order or to {@link DocumentProcessingStep#NOT_IMPLEMENTED}, and either way it appears in this
 * check. It cannot be forgotten in silence.</p>
 */
class DocumentProcessingStepCoverageTest {

    @Test
    @DisplayName("every declared stage is either executed or listed as not executed")
    void everyStageIsAccountedFor() {
        Set<DocumentProcessingStep.Step> declared =
                EnumSet.allOf(DocumentProcessingStep.Step.class);
        Set<DocumentProcessingStep.Step> executed =
                EnumSet.copyOf(DocumentProcessingPipeline.ORDER);
        Set<DocumentProcessingStep.Step> missing = EnumSet.noneOf(DocumentProcessingStep.Step.class);
        for (DocumentProcessingStep.Step step : declared) {
            if (!executed.contains(step) && !DocumentProcessingStep.NOT_IMPLEMENTED.contains(step)) {
                missing.add(step);
            }
        }
        assertThat(missing)
                .as("a stage that is neither run nor recorded as not run is a promise nobody keeps")
                .isEmpty();
    }

    @Test
    @DisplayName("a stage cannot be in both lists at once")
    void aStageIsNotBothExecutedAndSkipped() {
        for (DocumentProcessingStep.Step step : DocumentProcessingPipeline.ORDER) {
            assertThat(DocumentProcessingStep.NOT_IMPLEMENTED)
                    .as("%s is executed, so it must not also be listed as not implemented", step)
                    .doesNotContain(step);
        }
    }

    @Test
    @DisplayName("the execution order runs every stage once and in the order of the specification")
    void theOrderIsCompleteAndWithoutDuplicates() {
        assertThat(DocumentProcessingPipeline.ORDER)
                .as("a stage that runs twice would be a copy-paste, not a pipeline")
                .doesNotHaveDuplicates();
        assertThat(DocumentProcessingPipeline.ORDER).isNotEmpty();
        // The first five stages of §9.1 are the intake, in this order; a different order would extract text
        // before detecting the format.
        assertThat(DocumentProcessingPipeline.ORDER.subList(0, 5))
                .containsExactly(
                        DocumentProcessingStep.Step.SECURITY_SCAN,
                        DocumentProcessingStep.Step.HASH,
                        DocumentProcessingStep.Step.DUPLICATE_CHECK,
                        DocumentProcessingStep.Step.FORMAT_DETECT,
                        DocumentProcessingStep.Step.TEXT_EXTRACT);
    }

    @Test
    @DisplayName("the stages that do not run are exactly the five that are known not to run")
    void theKnownGapIsNamed() {
        // Naming them is the point: a reader can see what the product does not do without reading the
        // pipeline, and the report says the same.
        assertThat(DocumentProcessingStep.NOT_IMPLEMENTED)
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(
                        DocumentProcessingStep.Step.CONTRADICTION_CHECK,
                        DocumentProcessingStep.Step.ACTION_DERIVATION,
                        DocumentProcessingStep.Step.RISK_ASSESSMENT,
                        DocumentProcessingStep.Step.PERSIST,
                        DocumentProcessingStep.Step.INDEX));
    }
}

package de.buerokratiecopilot.documents.pipeline;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.matching.MatchingService;

/**
 * Proposes links to cases, contacts and contracts (spec §9.1 step 10, §9.5).
 *
 * <p>The stage always succeeds if it could run: finding no plausible link is a result, not an error,
 * and the review centre decides what to do about it. The proposals it produced — with their scores and
 * their evidence — are what a person sees and confirms.</p>
 */
@Component
public class MatchingStepHandler implements ProcessingStepHandler {

    private final MatchingService matching;

    private final de.buerokratiecopilot.documents.sender.SenderService senders;

    public MatchingStepHandler(MatchingService matching, de.buerokratiecopilot.documents.sender.SenderService senders) {
        this.matching = matching;
        this.senders = senders;
    }

    @Override
    public DocumentProcessingStep.Step step() {
        return DocumentProcessingStep.Step.MATCHING;
    }

    @Override
    public StepResult handle(ProcessingContext context) {
        // Who sent the letter is read first, so that the contact exists before anything is matched.
        senders.apply(context.document());
        MatchingService.Outcome outcome = matching.match(context.document(), context.version(), null);

        // A proposal a person has to judge means the document is not "ready": the next stage would
        // otherwise work with a link nobody confirmed. An exact reference that was linked
        // automatically still needs no review, which is why the check is on the proposals that
        // remain open rather than on their count.
        boolean openProposals = outcome.proposals().size() > outcome.autoLinked();
        return StepResult.succeeded(outcome.diagnostics(),
                openProposals ? Document.ProcessingStatus.REVIEW_REQUIRED : Document.ProcessingStatus.READY);
    }
}

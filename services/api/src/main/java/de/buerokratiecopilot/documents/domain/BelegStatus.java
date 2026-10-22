package de.buerokratiecopilot.documents.domain;

/**
 * Where a voucher stands in the office's work, not in the pipeline (Kanzlei edition, §8.1).
 *
 * <p>The pipeline status answers "what did the machine do with this file". A bookkeeper asks a different
 * question: "what is left to do with this voucher". The two are separate axes, and pretending one can answer
 * both is how a fully extracted file looks finished while nobody has touched it.</p>
 */
public enum BelegStatus {

    /** Arrived, not yet looked at. The queue a bookkeeper starts the morning with. */
    NEU,

    /** Checked and accepted as work: the voucher is in the period, waiting for its booking data. */
    OFFEN,

    /** Something does not add up - a missing amount, an unclear supplier, a doubtful date. Needs a person. */
    ZU_PRUEFEN,

    /** Left the office in a DATEV batch. The end of the queue, not the end of the voucher's life. */
    EXPORTIERT,

    /** The platform or a person could not process it. Visible, never silent. */
    FEHLER
}
